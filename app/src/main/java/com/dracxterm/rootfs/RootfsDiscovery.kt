package com.dracxterm.rootfs

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * RootFS Discovery (SRP: find + choose).
 *
 * Scans two places and picks the best match for this device ABI:
 *   1. `assets/rootfs/` inside the APK — empty in published builds, used by self-builds.
 *   2. [imageDir] on the device — where [RootfsDownloader] puts an image the user asked
 *      for, and where a user-supplied archive is imported to.
 *
 * Never throws; a missing or empty location simply contributes nothing.
 */
class RootfsDiscovery(private val ctx: Context) {

    companion object {
        const val ASSET_DIR = "rootfs"
        /** On-device location for images obtained at the user's request. */
        const val IMAGE_DIR = "rootfs-image"
    }

    /** Directory holding downloaded/imported images. Created lazily by the downloader. */
    fun imageDir(): File = File(ctx.filesDir, IMAGE_DIR)

    fun listAssets(): List<RootfsArchive> {
        val names = runCatching { ctx.assets.list(ASSET_DIR)?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
        return names.mapNotNull {
            RootfsArchive.classify(it, RootfsArchive.Source.Asset("$ASSET_DIR/$it"))
        }
    }

    fun listLocalFiles(): List<RootfsArchive> {
        val dir = imageDir()
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 0L } ?: emptyList()
        return files.mapNotNull {
            RootfsArchive.classify(it.name, RootfsArchive.Source.LocalFile(it))
        }
    }

    fun list(): List<RootfsArchive> = listAssets() + listLocalFiles()

    /**
     * Archive matching the device ABI, else the first available, else null.
     *
     * Assets are considered before on-device files: if someone deliberately built an APK
     * with an image inside it, that is the image they meant to use.
     */
    fun select(): RootfsArchive? {
        Log.i(ShellLocator.TAG, "[DISCOVERY] Scanning assets/$ASSET_DIR and ${imageDir().absolutePath}")
        val all = list()
        Log.i(ShellLocator.TAG, "[DISCOVERY] Archives found: " +
            if (all.isEmpty()) "(none)" else all.joinToString(", ") { "${it.fileName} @ ${it.describe()}" })
        if (all.isEmpty()) return null
        val abi = deviceArch()
        Log.i(ShellLocator.TAG, "[DISCOVERY] Device ABI: ${Build.SUPPORTED_ABIS?.joinToString(",")} -> arch=$abi")
        val chosen = all.firstOrNull { it.arch == abi } ?: all.first()
        Log.i(ShellLocator.TAG, "[DISCOVERY] Archive selected: ${chosen.fileName} " +
            "(arch=${chosen.arch}, ${chosen.compression}, from ${chosen.describe()})")
        return chosen
    }

    /** Deletes on-device images. Called after a successful extraction to reclaim space. */
    fun discardLocalImages(): Long {
        var freed = 0L
        (imageDir().listFiles() ?: emptyArray()).forEach { f ->
            val n = f.length()
            if (f.delete()) freed += n
        }
        if (freed > 0L) Log.i(ShellLocator.TAG, "[DISCOVERY] Reclaimed $freed bytes of image cache")
        return freed
    }

    private fun deviceArch(): String {
        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        return when {
            abis.any { it == "arm64-v8a" } -> "arm64"
            abis.any { it == "armeabi-v7a" } -> "armhf"
            abis.any { it == "x86_64" } -> "x86_64"
            abis.any { it == "x86" } -> "x86"
            else -> "unknown"
        }
    }
}
