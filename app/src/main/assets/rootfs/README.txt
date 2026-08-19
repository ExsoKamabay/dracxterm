dracXterm — Linux RootFS image location
=======================================

This folder is the ONE official source for the app's bundled Linux root
filesystem. Drop a single arm64 rootfs archive here before building the APK and
the RootFS Provisioning Engine will detect, validate, extract, configure and
launch it automatically on first run.

Supported archive names (auto-detected by RootfsDiscovery):
    debian-bookworm-arm64.tar.xz
    ubuntu-24.04-arm64.tar.xz
    alpine-arm64.tar.xz
    kali-nethunter-rootfs-nano-arm64.tar.xz
    <distro>-<...>-<arch>.tar.(xz|gz)   or   .tar / .tgz / .txz

Rules:
  * arm64 / aarch64 images match this build's ABI (arm64-v8a).
  * If several images are present, the one matching the device ABI is chosen.
  * If this folder is EMPTY (the shipped state), the app starts a BusyBox shell
    instead — it is still a fully working terminal; no error is shown.

The archive is extracted to  <filesDir>/rootfs  and Bootstrap then launches it
through PRoot. Provisioning runs only on first launch or when the environment is
found invalid; subsequent launches skip straight to the terminal.
