# Changelog

All notable changes to drac-Xterm are documented in this file.

## [1.1.0] - 2026-08-21

### Changed

- **The Linux root filesystem is no longer bundled in the APK.** `assets/rootfs/` ships
  empty. The app starts a BusyBox shell by default and offers, once, to fetch an ARM64
  image after explicit consent. Rationale and alternatives considered:
  `docs/adr/0001-rootfs-delivery.md`.
- Release signing now locates the keystore through the `DRACOS_STORE_FILE` Gradle
  property instead of a path inside the repository.
- Display name is `drac-Xterm` (was `xterm`, which collides with the long-standing X11
  terminal of that name). `rootProject.name` follows, so the Gradle project is `dracxterm`
  rather than the old `xterm`.
- `ndkVersion` is pinned to 27.0.12077973. AGP otherwise compiles the C++ engine with
  whatever NDK a machine happens to have installed, which makes the shipped native code
  depend on who ran the build.
- `.gitignore` now blocks rootfs archives, APK/AAB output and the whole
  `assets/rootfs/` directory except its README, so the 200 MB archive cannot be committed
  by accident a second time.
- `activity_provisioning.xml` is wrapped in a `ScrollView` so the consent copy stays
  readable on short screens and at large font scales.

### Added

- `RootfsCatalog` — pinned download URLs and SHA-256 digests, using immutable Kali
  release paths rather than the rolling `current/` directory.
- `RootfsDownloader` — HTTPS-only, resumable, writes to `.part` and promotes the file
  only after its digest matches the pin.
- `RootfsArchive.Source` — the provisioning pipeline now reads an archive from the APK
  assets or from a file on the device, identically.
- Consent screen in `ProvisioningActivity`; declining is remembered and leads to BusyBox.
- `fastlane/metadata/android/{en-US,id}/` — descriptions, changelogs, icon, screenshots.
- `docs/THIRD-PARTY-BINARIES.md` and `licenses/` — origin, version, checksum, licence and
  written source offer for every prebuilt binary in the APK.
- `docs/SECURITY-KEY-ROTATION.md`, `scripts/purge-keystore-history.sh` — remediation for
  the signing keystore that was committed to the public repository.
- `scripts/verify-rootfs-catalog.sh` — re-checks the pinned digests against upstream.
- `.github/workflows/release.yml` — signed release builds from a tagged commit. Refuses
  to publish unless the tag matches `versionName`, every locale has a changelog for the
  `versionCode` being released, the engine tests pass and the signature verifies; reports
  the APK size against the repository budget.
- `.github/workflows/ci.yml` — engine tests, the inclusion check and a full debug build on
  every push and pull request. No signing material is exposed to it, so forks can run it.
- `scripts/verify-izzy-metadata.py` — machine-checkable IzzyOnDroid rules: description and
  changelog limits, icon and screenshot dimensions, licence texts, an empty
  `assets/rootfs/`, and no signing material in the tree. Wired into both workflows, so the
  requirements are enforced rather than asserted in a document.
- `LICENSE` (Apache-2.0) at the repository root, and `licenses/BSD-3-Clause.txt` with
  android-shmem's actual copyright line — `NOTICE` referenced both, and neither was
  present in the working tree.

### Added — reproducible prebuilt binaries

- `prebuilts/` — every ARM64 binary in the APK can now be rebuilt from pinned upstream
  sources with the pinned NDK: BusyBox, talloc, PRoot + its loader, and libandroid-shmem.
  `./prebuilts/build.sh` builds all five; `--install` copies them into `jniLibs`.
  Previously none of them could be reproduced from this repository, which blocked any
  reproducible-build verification and left a BusyBox from 2018 in place with no way to
  replace it.
- The five artifacts are **bit-identical across build directories**, verified by building
  the whole set twice into paths of different lengths. Two causes of drift had to be
  fixed to get there: clang recording absolute source paths (`-ffile-prefix-map`), and
  BusyBox stamping the wall clock into its `--help` banner.
- CI rebuilds them weekly and re-checks reproducibility, so the recipes cannot rot
  silently against four moving upstreams.
- Six patches, each carrying its reasoning: BusyBox still assumes bionic lacks
  `strchrnul`/`getsid`/`sethostname`/`adjtimex` as it did in 2012, which breaks the
  static link; PRoot omits `<string.h>` in its ashmem extension and needs gawk-only awk
  extensions to build; android-shmem omits `<fcntl.h>` and hardcodes Termux's `$PREFIX/tmp`
  through `_PATH_TMP`, now read from `TMPDIR` at runtime instead.
- Correction to `docs/THIRD-PARTY-BINARIES.md`: the shipped `libandroid-shmem.so` comes
  from `termux/libandroid-shmem`, not `pelya/android-shmem`. PRoot's sysvipc extension
  calls `libandroid_shmat_fd()`, which only the fork has, so the documented upstream
  could not have produced the shipped binary.

### Changed — the APK now ships binaries built from source

- `app/src/main/jniLibs/arm64-v8a/` no longer contains the inherited Termux and 2018
  builds. All five binaries are the ones `prebuilts/build.sh` produces, with the SHA-256
  values recorded in `docs/THIRD-PARTY-BINARIES.md` and `NOTICE` updated to match.
- **BusyBox 1.29.3 (2018) → 1.38.0.** Seven years of upstream fixes, including the CVEs
  that made the old build a standing security item. The applet set is not identical:
  `ifconfig`, `route`, `netstat`, `ip`, `hush`, `logname`, `swapon`/`swapoff` and the SysV
  IPC tools are gone, each because it does not compile against bionic — reasons recorded
  per option in `prebuilts/busybox/android.fragment`.
- **PRoot is no longer a Termux binary.** The shipped one embedded
  `/data/data/com.termux/files/usr/lib` and `…/libexec/proot/loader`, which `Bootstrap`
  worked around at runtime. The replacement contains no Termux paths at all, so those
  workarounds are belt-and-braces rather than load-bearing.
- Verified on hardware before shipping: 20 checks on an Infinix X6726B (Android 15,
  arm64-v8a) covering the BusyBox shell and coreutils, a tar round-trip, PRoot's two
  built-in accelerators, and entering a minimal rootfs to run a shell, read through a
  bind mount and confirm `uid=0`. Re-runnable with
  `./scripts/release-prep.sh device-test`.
- Release APK: 9.24 MB.

### Security

- `OllamaInstaller.download()` followed the `Location` header of a redirect without
  checking its scheme, so a redirect to `http://` would have fetched an executable
  payload in cleartext. `RootfsDownloader` already refused every non-HTTPS hop; the two
  now behave identically. The SHA-256 pin meant this was never remote code execution, but
  it leaked what was being downloaded and contradicted the app's stated network
  behaviour. The same loop also passed a possibly-relative `Location` to `URL(String)`,
  which throws; it is now resolved against the current URL.
- `docs/PRIVACY-AUDIT.md` — every network call site and permission request site traced to
  what triggers it, so "no telemetry" and "never requested at startup" can be checked
  rather than taken on trust.

### Fixed

- `RootfsArchive.sizeBytes` did not compile: the `Source.Asset` branch read the `when`
  subject from inside a `runCatching` lambda, where the smart cast does not hold. Nothing
  in the repository could be built until this was fixed.
- Release-signing credentials were validated during Gradle *configuration*, which made
  every invocation fail without them — including `assembleDebug` and lint. The check now
  runs against the task graph and only fires when a release artifact is actually built.

### Removed

- `app/src/main/assets/rootfs/kali-nethunter-rootfs-nano-arm64.tar.xz` (206,922,656 bytes)
  and its Git LFS tracking rule.

### Known limitations

- Declining the Linux image is remembered and there is no in-app way to re-open the offer
  yet; an `xset` entry for it is not implemented. Clearing the app's data restores the
  prompt. Tracked in `docs/IZZYONDROID-SUBMISSION.md`.

### Upgrade note

An existing installation that already provisioned a rootfs keeps it: provisioning is
skipped whenever the marker and the runtime check both pass. Nothing is re-downloaded.

## [1.0.1] - 2026-08-21

### Added

- Bundled `kali-nethunter-rootfs-nano-arm64.tar.xz` for ARM64 Android devices at
  `app/src/main/assets/rootfs/`.
- Git LFS tracking for the bundled RootFS, keeping the repository cloneable while
  preserving the required release asset path.

### Release notes

Version 1.0.1 prepares drac-Xterm for a portable Kali NetHunter Nano environment
on `arm64-v8a` devices. On first launch, the existing RootFS provisioning pipeline
validates the bundled archive, extracts it safely into the app sandbox, configures
PRoot, and opens the Linux environment without requiring device root access.

The application version changes from `1.0.0` (version code `1`) to `1.0.1`
(version code `2`).
