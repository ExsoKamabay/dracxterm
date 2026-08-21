dracXterm - Linux RootFS image location
=======================================

This folder is intentionally EMPTY in published builds.

Since 1.1.0 no root filesystem is shipped inside the APK. The app starts a BusyBox
shell by default and offers, once, to fetch an ARM64 Linux image after the user
explicitly agrees; the download is verified against a pinned SHA-256 before it is
unpacked. See docs/adr/0001-rootfs-delivery.md for why.

Building your own APK with an image inside it
---------------------------------------------

That path still works and is unchanged. Drop a single arm64 rootfs archive in this
folder before building and the provisioning engine will detect, validate, extract,
configure and launch it on first run, with no download and no consent prompt.

Supported archive names (auto-detected by RootfsDiscovery):
    debian-bookworm-arm64.tar.xz
    ubuntu-24.04-arm64.tar.xz
    alpine-arm64.tar.xz
    kali-nethunter-rootfs-nano-arm64.tar.xz
    <distro>-<...>-<arch>.tar.(xz|gz)   or   .tar / .tgz / .txz

Rules:
  * arm64 / aarch64 images match this build's ABI (arm64-v8a).
  * If several images are present, the one matching the device ABI is chosen.
  * If this folder is EMPTY, the app starts a BusyBox shell instead - it is still a
    fully working terminal; no error is shown.

Do NOT commit an image here. A ~200 MB archive in git history bloats every clone and,
via Git LFS, exhausts the free bandwidth quota after a handful of fetches. Keep it in
your local working copy only; .gitignore already covers the archive extensions.

Where the app puts a downloaded image
-------------------------------------

    <filesDir>/rootfs-image/<name>.tar.xz     (deleted after a successful extraction)
    <filesDir>/rootfs/                        (the extracted environment)

The pinned download URLs and checksums live in
app/src/main/java/com/dracxterm/rootfs/RootfsCatalog.kt and can be re-checked against
upstream with scripts/verify-rootfs-catalog.sh.
