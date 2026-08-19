package com.dracxterm.rootfs

import android.content.Context
import android.system.Os
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/**
 * RootFS Extraction (SRP: decompress + untar safely) with forensic logging.
 * Streams asset -> (xz|gzip|plain) -> tar into a ".tmp" dir, STRIPS a single wrapping
 * top-level directory if present (e.g. `alpine-minirootfs-3.19/...`), then atomically
 * renames to the final rootfs. Guards tar-slip; preserves symlinks/hardlinks/mode; skips
 * device/FIFO nodes (PRoot virtualises /dev).
 */
class RootfsExtractor(private val ctx: Context) {

    private val tag = ShellLocator.TAG

    fun interface Progress { fun onProgress(entries: Long, bytes: Long, currentPath: String) }

    sealed class Result {
        data class Ok(val entries: Long) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun extract(archive: RootfsArchive, finalDir: File, progress: Progress?): Result {
        val tmpDir = File(finalDir.parentFile, finalDir.name + ".tmp")
        runCatching { if (tmpDir.exists()) tmpDir.deleteRecursively() }
        if (!tmpDir.mkdirs()) return Result.Failed("cannot create staging dir ${tmpDir.absolutePath}")
        val tmpCanonical = tmpDir.canonicalPath
        Log.i(tag, "[EXTRACTOR] Extraction started: ${archive.fileName} (${archive.compression})")
        Log.i(tag, "[EXTRACTOR] Destination directory: ${finalDir.absolutePath}")

        var count = 0L
        var total = 0L
        try {
            tarStream(archive).use { tar ->
                val buf = ByteArray(64 * 1024)
                var e: TarArchiveEntry? = tar.nextTarEntry
                while (e != null) {
                    val entry = e
                    val outFile = File(tmpDir, entry.name)
                    val canon = outFile.canonicalPath
                    if (canon != tmpCanonical && !canon.startsWith(tmpCanonical + File.separator)) {
                        return Result.Failed("unsafe path in archive: ${entry.name}")
                    }
                    when {
                        entry.isDirectory -> outFile.mkdirs()
                        entry.isSymbolicLink -> {
                            outFile.parentFile?.mkdirs()
                            if (ShellLocator.entryExists(outFile.absolutePath)) outFile.delete()
                            runCatching { Os.symlink(entry.linkName, outFile.absolutePath) }
                        }
                        entry.isLink -> {
                            outFile.parentFile?.mkdirs()
                            val src = File(tmpDir, entry.linkName)
                            runCatching { Os.link(src.absolutePath, outFile.absolutePath) }
                                .onFailure { runCatching { src.copyTo(outFile, overwrite = true) } }
                        }
                        entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> { /* skip */ }
                        else -> {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { os ->
                                var n = tar.read(buf)
                                while (n > 0) { os.write(buf, 0, n); total += n; n = tar.read(buf) }
                            }
                        }
                    }
                    if (!entry.isSymbolicLink) runCatching { Os.chmod(outFile.absolutePath, entry.mode and 0xFFF) }
                    count++
                    if (count % 250L == 0L) progress?.onProgress(count, total, entry.name)
                    e = tar.nextTarEntry
                }
            }
        } catch (t: Throwable) {
            runCatching { tmpDir.deleteRecursively() }
            return Result.Failed("extraction error: ${t.message}")
        }

        // Strip a single wrapping top-level directory (e.g. archives made with `tar czf x.tgz alpine/`).
        val stripped = stripSingleTopLevelDir(tmpDir)
        if (stripped != null) Log.i(tag, "[EXTRACTOR] Stripped wrapping top-level directory: $stripped")

        Log.i(tag, "[EXTRACTOR] Files extracted: $count (bytes=$total)")

        runCatching { if (finalDir.exists()) finalDir.deleteRecursively() }
        if (!tmpDir.renameTo(finalDir)) {
            val ok = runCatching { tmpDir.copyRecursively(finalDir, overwrite = true); tmpDir.deleteRecursively() }.isSuccess
            if (!ok) return Result.Failed("cannot finalise rootfs directory")
        }

        // Audit the real post-extraction layout.
        Log.i(tag, "[EXTRACTOR] rootfs/     = ${listing(finalDir)}")
        Log.i(tag, "[EXTRACTOR] rootfs/bin  = ${listing(File(finalDir, "bin"))}")
        Log.i(tag, "[EXTRACTOR] rootfs/usr/bin = ${listing(File(finalDir, "usr/bin"))}")

        progress?.onProgress(count, total, "done")
        return Result.Ok(count)
    }

    /**
     * If [dir] contains exactly one child and that child is a directory holding bin/ or usr/,
     * move the child's contents up into [dir] and return the stripped prefix name.
     */
    private fun stripSingleTopLevelDir(dir: File): String? {
        val children = dir.listFiles() ?: return null
        if (children.size != 1 || !children[0].isDirectory) return null
        val sub = children[0]
        val looksLikeRoot = File(sub, "bin").isDirectory || File(sub, "usr").isDirectory ||
                            ShellLocator.entryExists(File(sub, "bin/sh").absolutePath)
        if (!looksLikeRoot) return null
        val staging = File(dir, ".__pull__")
        if (!sub.renameTo(staging)) return null            // rename to avoid name clashes while moving
        (staging.listFiles() ?: emptyArray()).forEach { child ->
            val dest = File(dir, child.name)
            if (!child.renameTo(dest)) child.copyRecursively(dest, overwrite = true)
        }
        staging.deleteRecursively()
        return sub.name
    }

    private fun tarStream(archive: RootfsArchive): TarArchiveInputStream {
        val raw: InputStream = BufferedInputStream(ctx.assets.open(archive.assetPath), 1 shl 16)
        val decomp: InputStream = when (archive.compression) {
            RootfsArchive.Compression.XZ   -> XZCompressorInputStream(raw)
            RootfsArchive.Compression.GZIP -> GzipCompressorInputStream(raw)
            RootfsArchive.Compression.PLAIN -> raw
        }
        return TarArchiveInputStream(decomp)
    }

    private fun listing(d: File): String =
        if (!d.isDirectory) "(absent)" else (d.list()?.sorted()?.take(60)?.joinToString(", ") ?: "(empty)")
}
