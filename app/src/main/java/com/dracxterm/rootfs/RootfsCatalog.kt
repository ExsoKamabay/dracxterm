package com.dracxterm.rootfs

/**
 * The set of Linux images drac-Xterm offers to fetch, with a pinned URL and a pinned
 * SHA-256 for each.
 *
 * Two rules govern this file:
 *
 *  1. **Immutable URLs only.** Kali's `nethunter-images/current/` path is a moving
 *     target: the bytes behind it change every release, so a hash pinned against it
 *     would start failing without warning. Every entry here points at a versioned
 *     release directory, which never changes once published.
 *
 *  2. **The hash is the gate, not a formality.** [RootfsDownloader] refuses to hand an
 *     archive to the extractor unless the digest of the completed file matches
 *     [Entry.sha256] exactly. A mismatch means the download was corrupted, truncated,
 *     or intercepted, and it is treated as a hard failure.
 *
 * When bumping to a newer Kali release, take both the URL and the digest from the
 * SHA256SUMS file published alongside the images, and re-run
 * `scripts/verify-rootfs-catalog.sh` to confirm this file agrees with upstream.
 */
object RootfsCatalog {

    data class Entry(
        val id: String,
        /** Shown to the user in the consent screen. */
        val label: String,
        val fileName: String,
        val url: String,
        val sha256: String,
        /** Download size in bytes, as published upstream. Used for progress and for the
         *  free-space check before the transfer starts. */
        val approxBytes: Long,
        /** Rough space needed once extracted, used for the pre-flight capacity check. */
        val approxExtractedBytes: Long,
        val arch: String
    )

    private const val KALI_BASE =
        "https://kali.download/nethunter-images/kali-2026.2/rootfs"

    /**
     * arm64 entries only: every prebuilt binary in this APK is arm64-v8a
     * (`abiFilters` in app/build.gradle.kts), so offering another architecture would
     * download hundreds of megabytes that PRoot could never execute.
     */
    val ENTRIES: List<Entry> = listOf(
        Entry(
            id = "kali-nano-arm64",
            label = "Kali NetHunter nano (ARM64)",
            fileName = "kali-nethunter-rootfs-nano-arm64.tar.xz",
            url = "$KALI_BASE/kali-nethunter-rootfs-nano-arm64.tar.xz",
            sha256 = "2ea1c50446b9b35506c4b1cc84a731c752892baafe0dc2a1332e460c2d2a1e4e",
            approxBytes = 198_291_456L,          // 189.1 MiB as published
            approxExtractedBytes = 900_000_000L,
            arch = "arm64"
        ),
        Entry(
            id = "kali-minimal-arm64",
            label = "Kali NetHunter minimal (ARM64)",
            fileName = "kali-nethunter-rootfs-minimal-arm64.tar.xz",
            url = "$KALI_BASE/kali-nethunter-rootfs-minimal-arm64.tar.xz",
            sha256 = "d6403a5da175df325611d23af4b92330856059c45454eced7f4cdf3ca6df2e4e",
            approxBytes = 137_363_456L,          // 131.0 MiB as published
            approxExtractedBytes = 600_000_000L,
            arch = "arm64"
        )
    )

    fun default(): Entry = ENTRIES.first()

    fun byId(id: String): Entry? = ENTRIES.firstOrNull { it.id == id }

    /** Host shown in the consent screen, so the user sees where the bytes come from. */
    fun host(entry: Entry): String =
        runCatching { java.net.URI(entry.url).host ?: entry.url }.getOrDefault(entry.url)
}
