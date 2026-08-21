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

| File | What it is | Version (from binary) | Licence | SHA-256 |
|---|---|---|---|---|
| `libbusybox.so` | BusyBox multi-call binary, statically linked | `BusyBox v1.29.3-osm0sis (2018-11-17 02:42:59 AST)` | GPL-2.0-only | `d1ac865958598b77172410a23057843a1da5d784966e55488d262f759d5d7390` |
| `libproot.so` | PRoot user-space chroot | `5.1.0` (Termux build) | GPL-2.0-or-later | `5df456f971bf3f4822b27f1914acc1bf86b4041e8b8e5e36fd31e4df18514596` |
| `libproot-loader.so` | PRoot ELF loader stub, statically linked | ships with PRoot 5.1.0 | GPL-2.0-or-later | `44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04` |
| `libtalloc.so` | talloc memory pool allocator (PRoot dependency) | not embedded in the stripped binary | LGPL-3.0-or-later | `3c9b207c0a6ea2896b7523e03f55d9ab0d9e88baa115d4c32b84058ff4246fbb` |
| `libandroid-shmem.so` | System V shared-memory shim over Android ashmem | not embedded in the stripped binary | BSD-3-Clause | `84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731` |

## Corresponding source

Written offer, per GPL-2.0 §3(b) and LGPL-3.0 §4:

> For any binary listed above, the maintainer of drac-Xterm will provide the complete
> corresponding machine-readable source code, on the medium customarily used for
> software interchange, for a charge no more than the cost of physically performing
> the distribution. Requests: open an issue at
> <https://github.com/ExsoKamabay/dracxterm/issues>.

Upstream projects the binaries were built from:

| Component | Upstream source |
|---|---|
| BusyBox | <https://busybox.net/downloads/busybox-1.29.3.tar.bz2> — Android NDK build recipe: <https://github.com/osm0sis/android-busybox-ndk> (the `-osm0sis` suffix in the version banner identifies this build) |
| PRoot + loader | <https://github.com/termux/proot> (Termux package `proot`, upstream <https://github.com/proot-me/proot>) |
| talloc | <https://github.com/termux/termux-packages/tree/master/packages/libtalloc> (upstream <https://talloc.samba.org/>) |
| android-shmem | <https://github.com/pelya/android-shmem> |

Full licence texts are reproduced under `licenses/`.

## Provenance caveats — read before shipping

These are audit findings, not cosmetic notes.

1. **The binaries are Termux builds, not drac-Xterm builds.** `libproot.so`,
   `libtalloc.so` and `libandroid-shmem.so` still contain the hardcoded string
   `/data/data/com.termux/files/usr/lib`, and `libproot.so` additionally references
   `/data/data/com.termux/files/usr/libexec/proot/loader`. drac-Xterm works around this
   at runtime (`Bootstrap` sets `PROOT_LOADER` and the library search path), but the
   dependency on that workaround is undocumented anywhere in the code and will break
   silently if the environment setup changes.

2. **BusyBox 1.29.3 was released in 2018.** Multiple CVEs have been fixed in BusyBox
   since. Nothing in the repository pins or tracks this. Rebuilding against a current
   BusyBox release is a real security task, tracked separately from the F-Droid /
   IzzyOnDroid work.

3. ~~**No build recipe is committed.**~~ **Resolved 2026-08-22.** `prebuilts/` now
   builds all five from pinned upstream sources with the pinned NDK, and the output is
   bit-identical across build directories. See `prebuilts/README.md`.

   Two corrections to the upstream table above came out of writing it:

   - **android-shmem is Termux's fork, not pelya's.** PRoot's sysvipc extension calls
     `libandroid_shmat_fd()` and `libandroid_shmdt_fd()`, which exist only in
     <https://github.com/termux/libandroid-shmem>. Building against
     `pelya/android-shmem` fails outright. Same BSD 3-Clause licence, with Fredrik
     Fornwall's copyright added to Sergii Pylypenko's.
   - **BusyBox 1.29.3 is replaceable now.** The recipe targets 1.38.0.

### Rebuilding

```sh
./prebuilts/build.sh              # build all five, print SHA-256 of each
./prebuilts/build.sh --install    # and copy them into jniLibs
```

**The table above still describes what is shipped today**, which is still the inherited
Termux/2018 set. The recipe produces replacements; nothing is swapped in until they have
been exercised on real arm64 hardware — a BusyBox jump across seven years and a change
of PRoot provenance are not things to ship on a successful link. When they are installed,
update the SHA-256 column here in the same commit.
