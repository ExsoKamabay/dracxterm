# Privacy and permission audit

Performed 2026-08-22 against the tree at `main`. This exists because
`fastlane/metadata/android/*/full_description.txt` makes two claims to users —
"no analytics, no advertising, no tracking libraries, no telemetry, no account"
and "storage access is never requested at startup" — and a claim of that kind
should be checkable, not asserted.

Method: enumerate every network call site and every permission request site in
the source, then trace each one back to what triggers it. Grep patterns and
findings are recorded below so the audit can be repeated and disagreed with.

## Network

Every URL literal in `app/src/main/{java,cpp,assets}`:

| Destination | Reached from | Trigger |
|---|---|---|
| `https://kali.download/nethunter-images/kali-2026.2/rootfs/…` | `RootfsCatalog` → `RootfsDownloader` | The user taps "Download the Linux image" on the consent screen (`ProvisioningActivity`, `consentDownload` click listener). Nothing else calls it |
| `https://github.com/ollama/ollama/releases/download/$TAG/…` | `OllamaConfig` → `OllamaInstaller` | The user types `ollama` in the terminal and accepts the install (`OllamaLauncher`) |
| `http://127.0.0.1:11434` | `OllamaConfig` | The loopback address of the user's own `ollama serve`, contacted by the ollama binary itself. Never leaves the device |

The remaining hits are the JetBrains Mono licence text (`assets/fonts/OFL.txt`)
and two scheme-stripping expressions in `assets/ollama/launcher.sh`. Neither is
a request.

Only two classes open a connection at all: `RootfsDownloader` and
`OllamaInstaller`. Both use `HttpURLConnection`; there is no OkHttp, no
`WebView`, no `DownloadManager`, and no raw `Socket` anywhere in the app.

**Nothing runs at startup.** `MainActivity.onCreate` reaches neither. Both paths
begin at an explicit user action, and both verify the completed file against a
pinned SHA-256 before anything is unpacked or executed.

**No third-party SDK is present.** The dependency set is androidx-core,
androidx-appcompat, Material Components, commons-compress, tukaani-xz and
zstd-jni. None of them has a network or reporting component. There is no
Firebase, no Crashlytics, no ad mediation, and no analytics of any kind — the
app has no server to report to.

### Finding: the Ollama downloader followed redirects without checking the scheme

`OllamaInstaller.download()` disabled `instanceFollowRedirects` and then looped
on the `Location` header, opening whatever it named. `RootfsDownloader`
already rejected any hop that was not HTTPS; the Ollama path did not.

A redirect answered with `Location: http://…` would therefore have pulled an
executable payload in cleartext. The SHA-256 pin still refuses to install a
tampered file, so this was not remote code execution — but it exposed what the
user was downloading to anyone on the path, handed them a trivial denial of
service, and contradicted what the app tells users about its network use.

A second, smaller defect sat in the same loop: `Location` is allowed to be a
relative reference, and `URL(String)` throws on one, so a spec-legal redirect
would have failed the install with a confusing error.

Fixed: every hop is now rejected unless it is HTTPS, and `Location` is resolved
against the current URL with `URL(context, spec)`. The two downloaders now
behave identically.

## Permissions

| Permission | Why it is declared | When it is requested |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | The two opt-in downloads above, and the connectivity check that produces "No network connection" instead of a stack trace | Install-time, normal permissions; no runtime prompt exists |
| `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`), `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=29`) | Legacy shared storage on Android 12L and below | Only from the xset Storage screen |
| `READ_MEDIA_IMAGES` / `_VIDEO` / `_AUDIO` | The granular replacements on Android 13+ | Only from the xset Storage screen |
| `MANAGE_EXTERNAL_STORAGE` | A terminal is expected to reach shared storage by path. Without it `~/sdcard` cannot be a usable working directory | Only from the xset Storage screen, via the system "All files access" settings page |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | `TerminalService` keeps the app process alive so running shells and their PTYs survive a task switch | No prompt; `specialUse` is declared with a subtype string explaining exactly that |
| `POST_NOTIFICATIONS` | Visibility of the mandatory foreground-service notification | Standard runtime prompt. Denying it does not stop the service |

**Verified: no permission is requested at startup.** `requestStorageAccess()`
has exactly one call site — the Storage row of the xset dashboard, which the
user reaches by typing `xset` and navigating to it. `MainActivity.onCreate`
does not call it, directly or transitively. Denying storage leaves every other
feature working; `Bootstrap` re-checks `PermissionManager.storageGranted` live
rather than caching a stale answer.

`MANAGE_EXTERNAL_STORAGE` will still draw reviewer attention, and it should.
The defence is that it is optional, never requested unprompted, and that the
app is fully usable without it — which is only true for as long as it stays
that way.

## What this audit does not cover

- The five prebuilt binaries in `app/src/main/jniLibs/arm64-v8a/`. They are not
  built from this repository and were not disassembled. See
  `docs/THIRD-PARTY-BINARIES.md`.
- Anything the user installs into the Linux rootfs afterwards. That is a Kali
  system inside PRoot, with its own network behaviour, and drac-Xterm neither
  restricts nor observes it. The consent screen says so.
- A dynamic check. Confirming the app is silent on the wire needs a device and
  a proxy; this audit read the source. Running the release APK through
  [exodus](https://reports.exodus-privacy.eu.org/) before submission is still
  on the list in `docs/IZZYONDROID-SUBMISSION.md`.

## Repeating it

```sh
cd app/src/main
grep -rnoE 'https?://[^"'"'"' )]+' java cpp assets | sort -u
grep -rn 'URLConnection\|OkHttp\|Socket(\|openConnection\|DownloadManager\|WebView' java --include='*.kt'
grep -rn 'requestPermissions\|isExternalStorageManager\|ACTION_MANAGE_APP_ALL_FILES' java --include='*.kt'
```
