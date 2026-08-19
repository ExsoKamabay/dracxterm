package com.dracxterm.rootfs

/** A rootfs archive discovered under assets/rootfs/. Immutable descriptor only. */
data class RootfsArchive(
    val assetPath: String,          // e.g. "rootfs/alpine-arm64.tar.xz"
    val fileName: String,           // e.g. "alpine-arm64.tar.xz"
    val distro: String,             // best-effort label, e.g. "alpine"
    val arch: String,               // arm64 | armhf | x86_64 | x86 | unknown
    val compression: Compression
) {
    enum class Compression { XZ, GZIP, PLAIN }
}
