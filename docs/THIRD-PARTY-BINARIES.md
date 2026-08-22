# Third-party prebuilt binaries

drac-Xterm ships five prebuilt ELF binaries under `app/src/main/jniLibs/arm64-v8a/`.
They are packaged as `lib*.so` so that Android extracts them into `nativeLibraryDir`
as real, executable files (`android:extractNativeLibs="true"` +
`packaging.jniLibs.useLegacyPackaging = true`); despite the `.so` suffix, three of them
are ordinary executables rather than shared libraries.

Because drac-Xterm **distributes** these binaries inside its APK, drac-Xterm — not the
person who later redistributes the APK — carries the source-code obligations of their
licences. This file, together with `NOTICE`, is how that obligation is met.

## Inventory

Recorded from the binaries actually committed to this repository. Verify with
`sha256sum app/src/main/jniLibs/arm64-v8a/*.so`.

**Every one of these is built from source by `prebuilts/build.sh`** at a pinned upstream
revision with the NDK pinned in `app/build.gradle.kts`, reproducibly: the same digests
come out of any build directory and off a GitHub runner. See `prebuilts/README.md`.

| File | What it is | Version | Licence | SHA-256 |
|---|---|---|---|---|
| `libbusybox.so` | BusyBox multi-call binary, statically linked, 332 applets | `BusyBox v1.38.0 (drac-Xterm)` | GPL-2.0-only | `0d9f6306030e7af3ee8e98a8c6dc432b89158c0dd762d4fc65cae719152c6327` |
| `libproot.so` | PRoot user-space chroot | `v5.1.107.91` | GPL-2.0-or-later | `ec31292345954a947bcf7dd01c66a0f16b973f86582960333c5721b41ae97afc` |
| `libproot-loader.so` | PRoot ELF loader stub, statically linked | built with PRoot `v5.1.107.91` | GPL-2.0-or-later | `493c90afc5a88523d864007bca95172c1df9de3c28dcc06846dd1c371b308196` |
| `libtalloc.so` | talloc memory pool allocator (PRoot dependency) | 2.5.0, `SONAME libtalloc.so.2` | LGPL-3.0-or-later | `557e93989ea7b0ce471620f04941442e22fa6bdef8d2ac046a451e2a8fc356dc` |
| `libandroid-shmem.so` | System V shared-memory shim over Android ashmem | v0.7 | BSD-3-Clause | `c80ccfe16e30c28f00d3a46cb1f33983b7246a6d80ccd72806e104fcf4d26ee4` |

Exercised on real hardware before shipping: 20 checks on an Infinix X6726B
(Android 15, SDK 35, arm64-v8a) — BusyBox shell, coreutils, a tar round-trip and
applet count; PRoot version, both built-in accelerators, and entering a minimal
rootfs to run a shell, read through a bind mount and confirm `uid=0`. Re-runnable
with `./scripts/release-prep.sh device-test`.

## Corresponding source

Written offer, per GPL-2.0 §3(b) and LGPL-3.0 §4:

> For any binary listed above, the maintainer of drac-Xterm will provide the complete
> corresponding machine-readable source code, on the medium customarily used for
> software interchange, for a charge no more than the cost of physically performing
> the distribution. Requests: open an issue at
> <https://github.com/ExsoKamabay/dracxterm/issues>.

Upstream projects the binaries were built from:

Pinned in `prebuilts/manifest.env` — tarballs by SHA-256, git checkouts by full commit
SHA. `prebuilts/build.sh` fetches exactly these and refuses to continue on a digest
mismatch.

| Component | Upstream source | Pin |
|---|---|---|
| BusyBox | <https://busybox.net/downloads/busybox-1.38.0.tar.bz2> | `34f9ea6f…3bb2` |
| PRoot + loader | <https://github.com/termux/proot> (Termux's fork; upstream <https://github.com/proot-me/proot>) | `v5.1.107.91` = `61681c648119` |
| talloc | <https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz> (<https://talloc.samba.org/>) | `912afa23…b007` |
| android-shmem | <https://github.com/termux/libandroid-shmem> — **Termux's fork**, not `pelya/android-shmem`: PRoot's sysvipc extension calls `libandroid_shmat_fd()`, which only the fork defines | `v0.7` = `7f0bd7e2` |

Local modifications are the six patches under `prebuilts/*/patches/`, plus two headers
(`prebuilts/busybox/bionic-compat.h`, `prebuilts/talloc/bionic-replace.h`). Each carries
its reasoning; `prebuilts/README.md` summarises them. All are bionic-portability fixes,
none change behaviour.

Full licence texts are reproduced under `licenses/`.

## Provenance — resolved

These were audit findings. All three are now closed; the history is kept because it
explains why `prebuilts/` exists.

1. ~~**The binaries are Termux builds, not drac-Xterm builds.**~~ **Resolved.** The
   shipped `libproot.so` used to contain `/data/data/com.termux/files/usr/lib` and
   `…/libexec/proot/loader`, and `Bootstrap` worked around that at runtime by setting
   `PROOT_LOADER` and the library search path. Binaries built here contain no Termux
   paths — `prebuilts/proot/build.sh` fails the build if they do.

   Those `Bootstrap` workarounds are now belt-and-braces rather than load-bearing. They
   are harmless and still correct, but they no longer describe a defect being worked
   around.

2. ~~**BusyBox 1.29.3 was released in 2018.**~~ **Resolved.** Now 1.38.0, built from a
   pinned tarball. Two patches were needed, both because BusyBox's Android support was
   written when bionic was much smaller and still assumes it lacks `strchrnul`, `getsid`,
   `sethostname` and `adjtimex`; the duplicates break the static link against `libc.a`.

   The applet set is not identical to the 2018 build. `ifconfig`, `route`, `netstat`,
   `ip`, `hush`, `logname`, `swapon`/`swapoff` and the SysV IPC tools are gone, each for a
   reason recorded in `prebuilts/busybox/android.fragment` — every one of them fails to
   compile against bionic rather than having been dropped as unwanted.

3. ~~**No build recipe is committed.**~~ **Resolved.** `prebuilts/build.sh` rebuilds all
   five from pinned sources, bit-identically across build directories and across
   machines. See `prebuilts/README.md`.

### Rebuilding

```sh
./prebuilts/build.sh              # build all five, print SHA-256 of each
./prebuilts/build.sh --install    # and copy them into jniLibs
```

The table above describes what is shipped today. `./scripts/release-prep.sh tag` refuses
to publish if `jniLibs` and that table disagree, so the two cannot drift apart silently.

If you rebuild and install new binaries, re-run `./scripts/release-prep.sh device-test`
and update the SHA-256 column here in the same commit.
