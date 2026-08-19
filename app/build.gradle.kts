plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dracxterm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dracxterm"
        minSdk = 24            // forkpty/openpty + WindowInsets IME animation path supported; runtime-guarded below
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // AUDIT CONSTRAINT: every shipped prebuilt (proot/busybox/talloc/shmem/loader)
        // is arm64-v8a ONLY. Do not add other ABIs unless you also supply their binaries.
        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    // The prebuilt executables must exist as real files inside nativeLibraryDir so they
    // can be exec()'d on API 29+. useLegacyPackaging=true keeps them uncompressed/extracted.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // .so.2 style names are not extracted by the packager; we ship talloc as
            // libtalloc.so and recreate the libtalloc.so.2 symlink at runtime (Bootstrap).
        }
    }

    // Release signing.
    // Credentials MUST be supplied through Gradle properties; never hard-code them here.
    // Recommended location: ~/.gradle/gradle.properties
    //   DRACOS_STORE_PASSWORD=...
    //   DRACOS_KEY_ALIAS=dracos
    //   DRACOS_KEY_PASSWORD=...
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/dracos-release.keystore")
            storePassword = (project.findProperty("DRACOS_STORE_PASSWORD") as String?) ?: ""
            keyAlias = (project.findProperty("DRACOS_KEY_ALIAS") as String?) ?: ""
            keyPassword = (project.findProperty("DRACOS_KEY_PASSWORD") as String?) ?: ""
        }
    }

    buildTypes {
        debug {
            isJniDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // A Play-distributable release MUST be signed.
            // Fail the release build instead of silently producing an unsigned APK/AAB.
            val storePassword =
                project.findProperty("DRACOS_STORE_PASSWORD") as String?
            val keyAlias =
                project.findProperty("DRACOS_KEY_ALIAS") as String?
            val keyPassword =
                project.findProperty("DRACOS_KEY_PASSWORD") as String?

            require(!storePassword.isNullOrBlank()) {
                "Missing DRACOS_STORE_PASSWORD. Configure it in ~/.gradle/gradle.properties."
            }
            require(!keyAlias.isNullOrBlank()) {
                "Missing DRACOS_KEY_ALIAS. Configure it in ~/.gradle/gradle.properties."
            }
            require(!keyPassword.isNullOrBlank()) {
                "Missing DRACOS_KEY_PASSWORD. Configure it in ~/.gradle/gradle.properties."
            }

            signingConfig = signingConfigs.getByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }

    // rootfs archives must NOT be re-compressed by aapt: keeps openFd().length valid and
    // avoids double compression of already-compressed images bundled in assets/rootfs.
    androidResources { noCompress += listOf("xz", "gz", "tgz", "txz", "tar", "ttf") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // RootFS Provisioning Engine: stream-extract .tar.xz / .tar.gz images.
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")

    // Ollama Provisioning Engine: stream-decode the official .tar.zst release artifact.
    // Zstandard is the ONLY format the pinned Ollama artifact ships in, and this project had no
    // zstd decoder (commons-compress + tukaani cover xz/gzip only).
    //
    // VERSION PIN IS DELIBERATE AND LOAD-BEARING: 1.5.7-12, NOT 1.5.7-13.
    // zstd-jni commit a05b6ad (2026-07-30) raised the LIBRARY's own build from
    // `compileSdkVersion 26` to `compileSdk = 37`. AGP writes that value into the AAR's
    // aar-metadata.properties as `minCompileSdk`, and `checkDebugAarMetadata` enforces it on
    // every consumer - which is why 1.5.7-13 fails this module with
    //     "requires ... compile against version 37 or later of the Android APIs"
    // while :app compiles against 36 (the maximum AGP 8.11.1 supports).
    //     git merge-base --is-ancestor a05b6ad <tag>
    //       v1.5.7-1 ... v1.5.7-12 -> does NOT contain the bump
    //       v1.5.7-13              -> contains it
    // So 1.5.7-12 is the NEWEST release compatible with compileSdk 36. It was published from the
    // AGP 3.3.0 build, which predates AAR metadata entirely and therefore carries no
    // minCompileSdk constraint at all.
    //
    // DO NOT raise this to 1.5.7-13 (or later) unless compileSdk/AGP are independently upgraded.
    // -12 is not a functional downgrade: ZstdInputStream(InputStream)/read/close are identical in
    // -12 and -13 (so no source change was needed), both stream with the same fixed
    // ZSTD_DStreamInSize (~128 KiB) buffer, both are BSD 2-Clause, and -12 already links with
    // `-Wl,-z,max-page-size=16384`, satisfying Android 15's 16 KB page-size requirement.
    //
    // The AAR carries all four Android ABIs, but `abiFilters` above is arm64-v8a, so only the
    // arm64-v8a .so is packaged - no new architecture enters the APK. Exact pin, no "+" range,
    // no resolutionStrategy.force, no AAR-metadata suppression.
    implementation("com.github.luben:zstd-jni:1.5.7-12@aar")
}
