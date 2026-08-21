# CHANGES — Storage Access real visibility on the running session (2026-08-11)

## Symptom (runtime report)
`xset ▸ Storage Access = ON ▸ Close ▸ ls` did **not** show `~/sdcard` / `~/sdcard-1`, even though the
session was correctly preserved (same PTY/native handle, no restart). Data was never fabricated — it was
simply absent.

## Root cause (source-verified, `Bootstrap.kt`)
Storage setup was gated on `storageBackable`, a permission snapshot taken **once** at
`MainActivity.onCreate` (`captureStorageBackable` → `PermissionManager.storageGranted`). Two compounding
gates both fail in the normal first-grant flow (permission is granted through xset **after** the shell has
already spawned):

1. Spawn — `if (storageBackable) { … bind(…) }` was **false**, so **no** backing bind and **no**
   `.dracxterm/mnt/*` placeholder were created for the running proot.
2. Toggle — `applyStorageVisibility` hit `if (!storageBackable) return false` (stale snapshot, never
   refreshed) and returned before creating any symlink; even past it, the missing placeholder made the
   `backing.isDirectory` guard `continue`.

Net: nothing was ever created, so `ls` showed nothing. A running proot cannot be re-bound, so the design
could only ever work after an app relaunch — contradicting the "enable live in this session" requirement.

## Fix (minimal, `Bootstrap.kt` + `MainActivity.kt` messaging)
- **Bind storage UNCONDITIONALLY at spawn.** A proot bind is a pure path-translation rule; establishing it
  needs no read permission and fabricates nothing (reads stay OS-gated → an unpermitted source degrades to
  EACCES/empty, never mock data). The backing is therefore always present in the running namespace, so a
  later grant + a live home-symlink expose real `~/sdcard` on the **same** session — no respawn, no relaunch
  where the OS surfaces the grant to the running process.
- **Top-level, non-nested mountpoint `/mnt/<name>`** (was `~/.dracxterm/mnt/<name>`, nested under the
  `/home/dracos` bind). Overlapping/nested binds are a proot path-canonicalisation hazard (see Evidence).
- **Live permission re-check** in `applyStorageVisibility` (`PermissionManager.storageGranted`) instead of
  the stale start-time snapshot; the snapshot is now diagnostic-only.
- **`boundStorageNames`** tracks volumes actually bound at spawn; ON creates `~/<name> -> /mnt/<name>` only
  for those, so a volume that appears after spawn never gets a dangling link.
- **`STORAGE_TRACE` logging** at spawn and each toggle (structural facts only — no file contents/secrets):
  `adb logcat -s STORAGE_TRACE`.

## Evidence (host runtime, real proot 5.1.0)
On a single long-lived proot process (one "session"): a host-side `~/sdcard -> /mnt/sdcard` symlink created
**after** proot started became live-visible (`ls ~` showed `sdcard`); `cd ~/sdcard && ls` and `cat` returned
**real** content; removing the symlink hid it again; OFF→ON→OFF→ON all applied live on the same PID with no
restart. Observed proot-5.1.0 quirk: a bare `ls`/`stat` of a bind mountpoint reached via symlink (or nested
under another bind) can return ENOENT while the contents stay reachable via `cd`/`cat`. The mountpoint was
moved out of the home-bind subtree to reduce this; whether the shipped Termux proot exhibits the
symlink-mountpoint `ls` case is **DEVICE VERIFY REQUIRED** (proot-distro binds storage under home
successfully, so it likely does not).

## Verification status
- SOURCE VERIFIED — root cause + fix, symbol/type consistency, brace balance.
- HOST-RUNTIME VERIFIED — live symlink visibility, real-data reachability, OFF/ON toggling on one proot PID
  (real proot 5.1.0, x86_64).
- DEVICE/BUILD/APK **NOT VERIFIED** — no Android SDK/NDK/Gradle in the build environment; arm64 prebuilts
  cannot be linked on x86_64. Requires Dragon's local toolchain.
- ANDROID-SPECIFIC **NOT VERIFIED** — whether a runtime MANAGE_EXTERNAL_STORAGE grant is surfaced to the
  already-running app process (Layer A). If a given OS/OEM does not surface it, one relaunch is needed; the
  fix guarantees correct behaviour after that relaunch, and pure OFF/ON toggling thereafter is live.

## Files changed
- `app/src/main/java/com/dracxterm/Bootstrap.kt` — unconditional top-level binds, `boundStorageNames`, live
  permission re-check, `/mnt/<name>` symlink target, `logStorageTrace`.
- `app/src/main/java/com/dracxterm/MainActivity.kt` — `enableStorage` / `onStoragePermissionResult`
  messaging + doc aligned to the unconditional-bind model.
- `app/src/main/java/com/dracxterm/xset/XsetModules.kt` — one doc line (backing is now `/mnt/<name>`).

## Risks
- proot symlink-mountpoint `ls` quirk (above) — mitigated by non-nested mountpoint; verify on device.
- Android grant-propagation to a running process (above) — platform-dependent; relaunch fallback is correct.
- No change to session lifecycle, PTY, native handle, rendering, IME, or workspace paths.
