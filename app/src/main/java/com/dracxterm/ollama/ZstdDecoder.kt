package com.dracxterm.ollama

import com.github.luben.zstd.ZstdInputStream
import java.io.InputStream

/**
 * The ONE place in this codebase that names a Zstandard implementation.
 *
 * Layer separation (zstd directive §11): this object decodes ONLY the Zstandard compression frame.
 * It knows nothing about tar entries, and the tar layer knows nothing about zstd. Swapping the
 * decoder is a change to this file alone — [OllamaInstaller] only ever asks for "a streaming
 * InputStream over these compressed bytes".
 *
 * ---------------------------------------------------------------------------------------------
 * CURRENT IMPLEMENTATION: com.github.luben:zstd-jni:1.5.7-12@aar   (BSD 2-Clause)
 * ---------------------------------------------------------------------------------------------
 * Version 1.5.7-12 is pinned deliberately, NOT 1.5.7-13. Root cause, established by bisecting the
 * zstd-jni history:
 *
 *   commit a05b6ad "Update the Android build infrastructure to latest versions" (2026-07-30)
 *   replaced the library's Groovy build.gradle with build.gradle.kts and raised
 *       compileSdkVersion 26   ->   compileSdk = 37
 *
 *   git merge-base --is-ancestor a05b6ad <tag>:
 *       v1.5.7-1 … v1.5.7-12  -> does NOT contain it
 *       v1.5.7-13             -> CONTAINS it
 *
 * AGP records the library's compileSdk as `minCompileSdk` in the AAR's
 * META-INF/com/android/build/gradle/aar-metadata.properties, and `checkDebugAarMetadata` enforces
 * it on every consumer. That is why 1.5.7-13 demands compileSdk >= 37 while this app compiles
 * against 36 (the maximum AGP 8.11.1 supports). 1.5.7-12 was published from the AGP 3.3.0 build,
 * which predates AAR metadata entirely, so it carries no minCompileSdk constraint at all.
 *
 * 1.5.7-12 is the NEWEST release that satisfies compileSdk 36. It is not a downgrade in any
 * respect that matters here:
 *
 *   API SURFACE   identical for everything used below. `ZstdInputStream(InputStream)`,
 *                 `read(byte[],int,int)`, `read()` and `close()` are the same in -12 and -13;
 *                 -13 only adds @NotNull annotations and extra dictionary setters. Zero source
 *                 changes were needed to move from -13 to -12.
 *   STREAMING     ZstdInputStream extends FilterInputStream in both.
 *   MEMORY        both use the same fixed source buffer,
 *                 `srcBuffSize = (int) recommendedDInSize()` (ZSTD_DStreamInSize, ~128 KiB).
 *   ARM64 + 16KB  -12's CMakeLists already links with
 *                 `-fuse-ld=lld -Wl,-z,max-page-size=16384,-z,relro,-z,now`,
 *                 so its .so satisfies Android 15's 16 KB page-size requirement (this app targets
 *                 SDK 36). -13 additionally sets common-page-size=16384; max-page-size is the flag
 *                 that governs ELF LOAD segment alignment.
 *   LICENCE       BSD 2-Clause in both.
 *
 * The AAR carries all four Android ABIs; this module's `abiFilters += "arm64-v8a"` means only the
 * arm64-v8a .so is packaged. No new architecture enters the APK.
 */
object ZstdDecoder {

    /** Human-readable identity of the decoder, for logs and the install marker. */
    const val IMPLEMENTATION = "zstd-jni 1.5.7-12 (native, streaming)"

    /**
     * Wrap [compressed] in a streaming Zstandard decoder.
     *
     * The returned stream decodes incrementally with a bounded internal buffer: the caller may
     * hand it an arbitrarily large archive (the pinned Ollama artifact is ~1.44 GiB) without the
     * archive ever being read into a byte[] or fully decompressed to disk.
     *
     * A corrupt header, an invalid frame, a checksum failure or a premature EOF surfaces as an
     * IOException from a later read() — [OllamaInstaller] catches it, fails the install, and
     * deletes the staging tree, so a decode failure can never be mistaken for success.
     */
    fun decode(compressed: InputStream): InputStream = ZstdInputStream(compressed)
}
