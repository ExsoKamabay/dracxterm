# drac-Xterm — zstd-jni / compileSdk 36 build-failure resolution

**Date:** 2026-08-15

```
BUILD FAILURE ROOT CAUSE
-------------------------
Dependency:
com.github.luben:zstd-jni:1.5.7-13

Required compileSdk:
37+

Project compileSdk:
36

ROOT CAUSE:
Not a transitive resolution problem and not a Gradle problem. The constraint is baked into the
AAR by its own publisher.

zstd-jni commit a05b6ad, "Update the Android build infrastructure to latest versions"
(2026-07-30), replaced the library's Groovy build.gradle with build.gradle.kts and raised the
LIBRARY's own compile SDK:

        compileSdkVersion 26      ->      compileSdk = 37

AGP records a library's compileSdk as `minCompileSdk` inside the published AAR at
META-INF/com/android/build/gradle/aar-metadata.properties, and the consumer-side task
`checkDebugAarMetadata` enforces it. So every consumer of 1.5.7-13 is forced to compileSdk >= 37.
:app compiles against 36, which is also the maximum AGP 8.11.1 supports - hence the failure.

Introduced by (single declaration, direct, not transitive):
    app/build.gradle.kts, dependencies { }
        implementation("com.github.luben:zstd-jni:1.5.7-13@aar")
It was added by me in the Ollama work earlier today. Nothing else in the project references it,
no other dependency pulls it in, and no resolutionStrategy is involved - the `@aar` classifier
means Gradle resolves exactly the coordinate written there. Confirmable with:
    ./gradlew :app:dependencyInsight --dependency zstd-jni --configuration debugRuntimeClasspath

MY ERROR: I verified the library's functionality, ABI, licence, streaming behaviour and minSdk,
but I did not check its AAR metadata `minCompileSdk` against this project's compileSdk. That
check is exactly what `checkDebugAarMetadata` performs, and it is the one I skipped.


SELECTED SOLUTION
-----------------
zstd-jni version:
1.5.7-12          (repinned from 1.5.7-13; exact pin, no range, no force, no suppression)

Why:
1.5.7-12 is the NEWEST published release that is compatible with compileSdk 36. Established by
bisecting the upstream history rather than by guessing:

    git merge-base --is-ancestor a05b6ad <tag>
        v1.5.7-1 … v1.5.7-9, v1.5.7-11, v1.5.7-12  ->  does NOT contain the compileSdk=37 bump
        v1.5.7-13                                   ->  CONTAINS it

At tag v1.5.7-12 the library still built with `compileSdkVersion 26`, AGP 3.3.0 and Gradle 4.10.1.
AGP 3.3.0 predates AAR metadata entirely (introduced in AGP 4.1), so the 1.5.7-12 AAR carries no
minCompileSdk constraint at all - `checkDebugAarMetadata` has nothing to enforce.

It is not a functional downgrade in any dimension this project depends on:

  API SURFACE    Identical for everything used. Both versions expose
                 `public class ZstdInputStream extends FilterInputStream`,
                 `ZstdInputStream(InputStream)`, `read(byte[],int,int)`, `read()`, `close()`.
                 1.5.7-13 only adds @NotNull annotations and extra dictionary setters.
                 ** Zero source changes were required to move from -13 to -12. **
  STREAMING      Both: ZstdInputStream extends FilterInputStream, incremental decode.
  BOUNDED MEMORY Both: `private static final int srcBuffSize = (int) recommendedDInSize();`
                 (ZSTD_DStreamInSize, ~128 KiB fixed source buffer), and
                 `srcSize = in.read(src, 0, srcBuffSize)` in the read loop.
  ARM64          Built through externalNativeBuild/CMake for all Android ABIs; `abiFilters`
                 selects arm64-v8a only.
  16 KB PAGES    v1.5.7-12's CMakeLists already links with
                 `-fuse-ld=lld -Wl,-z,max-page-size=16384,-z,relro,-z,now`
                 so its .so meets Android 15's 16 KB page-size requirement (this app targets
                 SDK 36). This was checked specifically because an AGP-3.3.0-era AAR would
                 otherwise be a plausible 4 KB-alignment hazard. It is not.
  LICENCE        BSD 2-Clause, unchanged.

Options A-D of your §8 were NOT reached: a compatible zstd-jni release exists, so the first
solution rule applies. Nothing was upgraded to accommodate the dependency.


TOOLCHAIN CHANGES
-----------------
compileSdk:   36      -> unchanged   (verified: app/build.gradle.kts:8)
targetSdk:    36      -> unchanged   (verified: app/build.gradle.kts:13)
minSdk:       24      -> unchanged   (verified: app/build.gradle.kts:12)
AGP:          8.11.1  -> unchanged   (verified: build.gradle.kts:7)
Kotlin:       1.9.24  -> unchanged   (verified: build.gradle.kts:9)
Java:         17      -> unchanged   (verified: app/build.gradle.kts:96-99)
ABI:          arm64-v8a -> unchanged (verified: app/build.gradle.kts:19)
Gradle wrapper / NDK / build tools:  untouched, not read, not modified.

No `android.suppressUnsupportedCompileSdk`. No disabling of `checkDebugAarMetadata`. No AAR
metadata exclusion. No `resolutionStrategy.force`. No edited or hand-patched Maven artifacts.
The full diff of app/build.gradle.kts against the pre-Ollama baseline is additive only: one
dependency line plus its comment block. Every other line is byte-identical.


BUILD VERIFICATION
------------------
./gradlew clean:            NOT RUN - BLOCKED
./gradlew assembleDebug:    NOT RUN - BLOCKED
Second assembleDebug:       NOT RUN - BLOCKED

I have no Android SDK/NDK in this sandbox and no shell on your machine (the device bridge is
file-transfer only), so I cannot run Gradle. I will not report PASS for a command I did not run.

Additionally BLOCKED: Maven Central is unreachable from this sandbox (proxy returns 403 for
repo1.maven.org, repo.maven.apache.org and the Google mirror), so I could NOT open the published
zstd-jni-1.5.7-12.aar and read its aar-metadata.properties, jni/ layout or .so alignment directly.
Every claim above about 1.5.7-12 comes from the upstream git tree at tag v1.5.7-12, and from the
Maven Central directory listing which confirms the artifact exists:

    zstd-jni-1.5.7-12.aar    1,164,323 bytes    2026-07-26 18:18

That is strong evidence, not proof of the published bytes. Please run the checks below.


ZSTD VERIFICATION
-----------------
Streaming:        PASS (SOURCE VERIFIED at tag v1.5.7-12: FilterInputStream, incremental read loop)
ARM64:            PASS (SOURCE VERIFIED: externalNativeBuild/CMake; abiFilters restricts to arm64-v8a)
                       - packaged .so in the APK: DEVICE/BUILD-VERIFY
.tar.zst:         PASS (SOURCE VERIFIED: same ZstdInputStream API the pipeline already compiles
                       against; ZstdDecoder.kt + TarArchiveInputStream unchanged)
Bounded memory:   PASS (SOURCE VERIFIED: fixed srcBuffSize = recommendedDInSize(), ~128 KiB)
                       - measured peak RSS on device: DEVICE-VERIFY


OLLAMA VERIFICATION
-------------------
which ollama:     NOT RUN (DEVICE-VERIFY) - requires a successful build and the device
ollama --version: NOT RUN (DEVICE-VERIFY) - requires a successful build and the device


REGRESSION
----------
Terminal:   NOT TESTED (DEVICE-VERIFY)   - source byte-identical
PTY:        NOT TESTED (DEVICE-VERIFY)   - source byte-identical
Keyboard:   NOT TESTED (DEVICE-VERIFY)   - source byte-identical
Toolbar:    NOT TESTED (DEVICE-VERIFY)   - source byte-identical
Workspace:  NOT TESTED (DEVICE-VERIFY)   - source byte-identical
Rootfs:     NOT TESTED (DEVICE-VERIFY)   - source byte-identical

This fix touched no stable component. It is a dependency version change plus one new isolated
file; the terminal engine, PTY, JNI, C++, cursor, keyboard, toolbar, WindowInsets, workspace,
BusyBox, PRoot and rootfs provisioning were not opened.


CHANGED FILES
-------------
app/build.gradle.kts
    zstd-jni 1.5.7-13 -> 1.5.7-12, with the bisect evidence recorded inline so the pin cannot be
    "helpfully" bumped later without re-reading why. Only the dependency block changed.

app/src/main/java/com/dracxterm/ollama/ZstdDecoder.kt      (NEW)
    The single place in the codebase that names a Zstandard implementation. Directly serves your
    §11 (zstd layer and tar layer must be separate) and §15 (dependency isolation): OllamaInstaller
    previously constructed `ZstdInputStream` inline, mixing the layers. Now it asks ZstdDecoder for
    a streaming InputStream. Swapping the decoder - if 1.5.7-12 ever fails verification - is a
    change to this one file.

app/src/main/java/com/dracxterm/ollama/OllamaInstaller.kt
    Three lines: dropped the direct `com.github.luben.zstd.ZstdInputStream` import, call
    `ZstdDecoder.decode(raw)` in tarStream(), and log the decoder identity when extraction starts.
    The pipeline, the filter policy, the integrity check, the validation and the atomic promotion
    are unchanged.

UNRELATED CHANGES:
NONE
```

---

## What to run, in order

```sh
cd ~/Desktop/terminal

# 1. Confirm where the dependency comes from and which version resolves.
./gradlew :app:dependencyInsight --dependency zstd-jni --configuration debugRuntimeClasspath

# 2. Clean build.
./gradlew clean
./gradlew assembleDebug

# 3. Reproducibility (must not depend on stale outputs).
./gradlew assembleDebug
```

### If the build succeeds, verify the artifact rather than trusting `BUILD SUCCESSFUL`

```sh
# a) The resolved AAR's metadata - this is the field that caused the failure.
find ~/.gradle/caches/modules-2 -name 'zstd-jni-1.5.7-12.aar' -print
unzip -p <that path> META-INF/com/android/build/gradle/aar-metadata.properties 2>/dev/null \
  || echo "no aar-metadata.properties  -> no minCompileSdk constraint (expected for 1.5.7-12)"

# b) What the AAR actually contains.
unzip -l <that path> | grep -E 'jni/|classes.jar|AndroidManifest'

# c) The APK must remain arm64-only, and must now contain libzstd-jni.
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep '\.so$'
#    expect ONLY lib/arm64-v8a/...  including lib/arm64-v8a/libzstd-jni-1.5.7-12.so

# d) 16 KB page alignment of the shipped .so (Android 15+ requirement; app targets SDK 36).
unzip -o app/build/outputs/apk/debug/app-debug.apk 'lib/arm64-v8a/libzstd-jni*.so' -d /tmp/zcheck
readelf -lW /tmp/zcheck/lib/arm64-v8a/libzstd-jni*.so | awk '/LOAD/ {print $NF}'
#    expect 0x4000 (16384). 0x1000 would mean 4 KB alignment and a load failure on 16 KB devices.
```

### Then the runtime chain, on the device

```sh
which ollama
ollama --version
```

## If 1.5.7-12 fails any of those checks

Do **not** raise `compileSdk`. Tell me which check failed and I will switch `ZstdDecoder.kt`
(one file) to the next option in your §8 order:

- **Option A** — an alternative Zstandard AAR that declares no `minCompileSdk` above 36.
- **Option B** — build zstd into the project's **existing** native build. This project already has
  `app/src/main/cpp/CMakeLists.txt`, the NDK, `externalNativeBuild` and `abiFilters arm64-v8a`, so
  a vendored zstd decompressor plus a small JNI streaming wrapper stays entirely inside the
  project's own toolchain: no AAR metadata, no foreign ABIs, no upstream version drift, and the
  16 KB alignment becomes ours to set. Larger diff, zero external Android-API coupling.
- **Option C** — a pure-Java streaming decoder. Immune to this whole failure class (a plain JAR has
  no AAR metadata), but must be checked for `sun.misc.Unsafe` usage, which Android's hidden-API
  restrictions can block, and for a Java-17-compatible release line.
- **Option D** — toolchain upgrade. Last resort, and it needs its own architectural justification,
  not a dependency's convenience.

## Note on the earlier report

`docs/OLLAMA-INTEGRATION-2026-08-15.md` §4 still records the 1.5.7-13 pin and states
`FINAL APK IMPACT: NOT MEASURED`. That section is superseded by this document. I have not edited
it, so the original record of what I claimed - and what I got wrong - stays intact.
