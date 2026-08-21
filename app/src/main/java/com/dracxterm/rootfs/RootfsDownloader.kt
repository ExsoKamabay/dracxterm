package com.dracxterm.rootfs

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Fetches a Linux image the user has explicitly asked for.
 *
 * Contract, in order of importance:
 *
 *  1. **Never runs on its own.** The caller must have obtained explicit consent first
 *     (see ProvisioningActivity). Nothing here is triggered by app startup.
 *  2. **HTTPS only.** A plaintext URL, or a redirect that downgrades to plaintext, is a
 *     hard failure rather than something to shrug at: this file becomes executable code
 *     inside the user's sandbox.
 *  3. **A partial file is never promoted.** Bytes land in `<name>.part`. Only after the
 *     SHA-256 of the completed file matches the pinned digest is it renamed to the name
 *     [RootfsDiscovery] scans for. An interrupted transfer therefore cannot be mistaken
 *     for a usable image on the next launch.
 *  4. **Resumable.** A leftover `.part` is continued with a Range request when the server
 *     honours it, and restarted from zero when it does not.
 */
class RootfsDownloader(private val ctx: Context) {

    private val tag = ShellLocator.TAG

    fun interface Progress {
        /** [total] is -1 when the server does not report a length. */
        fun onProgress(downloaded: Long, total: Long)
    }

    sealed class Result {
        data class Ok(val file: File) : Result()
        object Cancelled : Result()
        data class Failed(val reason: String) : Result()
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER = 128 * 1024
        const val USER_AGENT = "drac-Xterm"
    }

    /**
     * Downloads [entry] into [RootfsDiscovery.IMAGE_DIR].
     *
     * Returns [Result.Ok] only when the completed file's digest matches
     * [RootfsCatalog.Entry.sha256]. Blocking; call from a background thread.
     */
    fun download(
        entry: RootfsCatalog.Entry,
        progress: Progress?,
        isCancelled: () -> Boolean
    ): Result {
        val dir = File(ctx.filesDir, RootfsDiscovery.IMAGE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return Result.Failed("cannot create ${dir.absolutePath}")

        val target = File(dir, entry.fileName)
        val part = File(dir, entry.fileName + ".part")

        // An image from a previous run that already matches: nothing to do.
        if (target.isFile && target.length() > 0L) {
            Log.i(tag, "[DOWNLOAD] ${target.name} already present, verifying")
            return when (val v = verify(target, entry.sha256, isCancelled)) {
                is Verify.Match -> Result.Ok(target)
                Verify.Cancelled -> Result.Cancelled
                is Verify.Mismatch -> {
                    Log.w(tag, "[DOWNLOAD] existing file failed verification (${v.actual}); discarding")
                    target.delete()
                    download(entry, progress, isCancelled)
                }
            }
        }

        // Capacity: the archive plus what it expands to, with a little headroom.
        val need = entry.approxBytes + entry.approxExtractedBytes
        val free = runCatching { StatFs(ctx.filesDir.absolutePath).availableBytes }
            .getOrDefault(Long.MAX_VALUE)
        if (free in 0 until need) {
            return Result.Failed(
                "not enough free space: about ${human(need)} is needed, ${human(free)} available"
            )
        }

        val existing = if (part.isFile) part.length() else 0L
        if (existing > 0L) Log.i(tag, "[DOWNLOAD] resuming ${part.name} at $existing bytes")

        var conn: HttpURLConnection? = null
        try {
            val opened = openWithRedirects(entry.url, existing) ?: return Result.Failed("too many redirects")
            conn = opened.connection
            val code = conn.responseCode

            val appending: Boolean
            when {
                existing > 0L && code == HttpURLConnection.HTTP_PARTIAL -> appending = true
                code == HttpURLConnection.HTTP_OK -> {
                    // Server ignored the Range header: start over rather than concatenating
                    // a fresh body onto a partial file and producing a corrupt archive.
                    appending = false
                    if (existing > 0L) Log.i(tag, "[DOWNLOAD] server ignored Range; restarting from 0")
                }
                else -> return Result.Failed("server returned HTTP $code")
            }

            val reported = conn.contentLengthLong
            val total = when {
                reported < 0L -> -1L
                appending -> existing + reported
                else -> reported
            }

            var written = if (appending) existing else 0L
            FileOutputStream(part, appending).use { out ->
                conn.inputStream.use { ins ->
                    val buf = ByteArray(BUFFER)
                    var lastReport = 0L
                    while (true) {
                        if (isCancelled()) {
                            Log.i(tag, "[DOWNLOAD] cancelled by user at $written bytes (partial file kept)")
                            return Result.Cancelled
                        }
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (written - lastReport >= 512 * 1024) {
                            lastReport = written
                            progress?.onProgress(written, total)
                        }
                    }
                    out.fd.sync()
                }
            }
            progress?.onProgress(written, total)

            if (total > 0L && written != total) {
                return Result.Failed("transfer ended early: $written of $total bytes")
            }
        } catch (t: Throwable) {
            // The .part file is deliberately kept so the next attempt can resume.
            return Result.Failed("download failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { conn?.disconnect() }
        }

        Log.i(tag, "[DOWNLOAD] verifying SHA-256 of ${part.name}")
        return when (val v = verify(part, entry.sha256, isCancelled)) {
            Verify.Cancelled -> Result.Cancelled
            is Verify.Mismatch -> {
                // A wrong digest means the bytes are not what we pinned. Keeping them
                // around would only invite a later attempt to extract them.
                part.delete()
                Result.Failed(
                    "the downloaded file does not match its expected checksum and was discarded " +
                        "(expected ${entry.sha256.take(16)}…, got ${v.actual.take(16)}…)"
                )
            }
            is Verify.Match -> {
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) return Result.Failed("cannot finalise ${target.name}")
                Log.i(tag, "[DOWNLOAD] ${target.name} ready (${human(target.length())})")
                Result.Ok(target)
            }
        }
    }

    /** Removes any partial transfer for [entry]. */
    fun discardPartial(entry: RootfsCatalog.Entry) {
        val part = File(File(ctx.filesDir, RootfsDiscovery.IMAGE_DIR), entry.fileName + ".part")
        if (part.exists() && part.delete()) Log.i(tag, "[DOWNLOAD] discarded ${part.name}")
    }

    /** Bytes already fetched for [entry], for showing "resume" instead of "download". */
    fun partialBytes(entry: RootfsCatalog.Entry): Long =
        File(File(ctx.filesDir, RootfsDiscovery.IMAGE_DIR), entry.fileName + ".part")
            .takeIf { it.isFile }?.length() ?: 0L

    // ---------------------------------------------------------------- internals

    private class Opened(val connection: HttpURLConnection)

    /**
     * Follows redirects manually. HttpURLConnection will not follow a cross-protocol
     * redirect, and we do not want it to: every hop must stay on HTTPS.
     */
    private fun openWithRedirects(startUrl: String, resumeFrom: Long): Opened? {
        var url = startUrl
        repeat(MAX_REDIRECTS) {
            val parsed = URL(url)
            if (!parsed.protocol.equals("https", ignoreCase = true)) {
                throw SecurityException("refusing a non-HTTPS URL: $url")
            }
            val conn = (parsed.openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "identity")   // keep contentLength honest
                if (resumeFrom > 0L) setRequestProperty("Range", "bytes=$resumeFrom-")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 || code == 308
            ) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw java.io.IOException("redirect without Location")
                url = URL(parsed, location).toString()
                Log.i(tag, "[DOWNLOAD] redirected to $url")
                return@repeat
            }
            return Opened(conn)
        }
        return null
    }

    private sealed class Verify {
        object Cancelled : Verify()
        data class Match(val digest: String) : Verify()
        data class Mismatch(val actual: String) : Verify()
    }

    private fun verify(file: File, expected: String, isCancelled: () -> Boolean): Verify {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER)
        file.inputStream().use { ins: InputStream ->
            while (true) {
                if (isCancelled()) return Verify.Cancelled
                val n = ins.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return if (actual.equals(expected, ignoreCase = true)) Verify.Match(actual)
        else Verify.Mismatch(actual)
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes.toDouble() / (1L shl 20))
        else -> "$bytes B"
    }
}
