package com.dracxterm.rootfs

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * RootFS Discovery (SRP: find + choose).
 * Lists archives bundled in assets/rootfs and picks the best match for this device ABI.
 * Never throws; a missing/empty folder simply yields an empty list.
 */
class RootfsDiscovery(private val ctx: Context) {

    companion object { const val ASSET_DIR = "rootfs" }

    fun list(): List<RootfsArchive> {
        val names = runCatching { ctx.assets.list(ASSET_DIR)?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
        return names.mapNotNull { classify(it) }
    }

    /** Archive matching the device ABI, else the first available, else null. */
    fun select(): RootfsArchive? {
        Log.i(ShellLocator.TAG, "[DISCOVERY] Scanning assets/$ASSET_DIR")
        val all = list()
        Log.i(ShellLocator.TAG, "[DISCOVERY] Archives found: " +
            if (all.isEmpty()) "(none)" else all.joinToString(", ") { it.fileName })
        if (all.isEmpty()) return null
        val abi = deviceArch()
        Log.i(ShellLocator.TAG, "[DISCOVERY] Device ABI: ${Build.SUPPORTED_ABIS?.joinToString(",")} -> arch=$abi")
        val chosen = all.firstOrNull { it.arch == abi } ?: all.first()
        Log.i(ShellLocator.TAG, "[DISCOVERY] Archive selected: ${chosen.fileName} (arch=${chosen.arch}, ${chosen.compression})")
        return chosen
    }

    private fun classify(name: String): RootfsArchive? {
        val lower = name.lowercase()
        val comp = when {
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> RootfsArchive.Compression.XZ
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> RootfsArchive.Compression.GZIP
            lower.endsWith(".tar")                              -> RootfsArchive.Compression.PLAIN
            else -> return null
        }
        val arch = when {
            lower.contains("arm64") || lower.contains("aarch64") -> "arm64"
            lower.contains("armhf") || lower.contains("armv7")   -> "armhf"
            lower.contains("x86_64") || lower.contains("amd64")  -> "x86_64"
            lower.contains("i386") || lower.contains("i686")     -> "x86"
            else -> "unknown"
        }
        val distro = lower.substringBefore('-').ifEmpty { "linux" }
        return RootfsArchive("$ASSET_DIR/$name", name, distro, arch, comp)
    }

    private fun deviceArch(): String {
        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        return when {
            abis.any { it == "arm64-v8a" }   -> "arm64"
            abis.any { it == "armeabi-v7a" } -> "armhf"
            abis.any { it == "x86_64" }      -> "x86_64"
            abis.any { it == "x86" }         -> "x86"
            else -> "unknown"
        }
    }
}
