# Architecture

## Data flow

```
                     ┌─────────────────────── UI thread ───────────────────────┐
 key / IME  ──────►  TerminalView.onKeyDown / InputConnection
                          │  write(bytes)
                          ▼
                     TerminalSession.write ──JNI──► Session::write ──► Pty::writeMaster
                                                                            │
                                                                     (kernel PTY)
                                                                            │
 busybox ash / proot  ◄──────────────────────────────────────────────────┘
        │ stdout/stderr
        ▼
   (kernel PTY master)
        │
        ▼   ┌───────────────── reader thread (native) ────────────────┐
   Pty::readMaster ──► Session::readerLoop ──► Terminal::feed (mutex)
                                                    │
                                                    ▼
                                             AnsiParser::feed
                                                    │
                                                    ▼
                                             ScreenBuffer (grid + scrollback)
                                                    │
                              takeDirty()? generation_.fetch_add(1)
                          ┌─────────────────────────┘
                          ▼   (vsync poll)
   TerminalView.doFrame ─ if generation changed ─► nativeSnapshot ─► onDraw
```

## Threads

- **UI thread** — input encoding, `nativeWrite`, `nativeResize`, and rendering.
  Rendering never blocks on I/O; it only reads a snapshot under the engine mutex.
- **Reader thread** (one per session, created in `Session::start`) — `poll()`s the PTY
  master, feeds bytes to the parser, bumps the generation counter. Exits cleanly on
  `POLLHUP`/EOF or when `stop()` closes the fd.

Synchronization: a single `std::mutex` in `Terminal` guards the `ScreenBuffer`. Writes
(parser) and reads (snapshot) both take it. The generation counter is a relaxed atomic —
it only needs to signal "something changed", not order memory. The reader thread bumps it
only when `Terminal::takeDirty()` reports the screen actually changed (damage tracking),
so escape queries that produce no visible change do not trigger repaints.

UI-thread mutations (history scroll, selection) change the buffer without going through
the reader thread's generation bump, so `TerminalView` re-snapshots and invalidates
immediately after each such action (`uiRefresh()`). Small responses the program requests
(`DA`/`DSR`, mouse, focus, clipboard) are written straight back to the PTY by the parser
through a responder callback installed by `Session`.

## Module responsibilities

| Path | Responsibility |
|------|----------------|
| `cpp/native-lib.cpp` | JNI marshalling only. No terminal logic. |
| `cpp/engine/pty/` | `forkpty`, exec, `TIOCSWINSZ` resize, read/write master. |
| `cpp/engine/parser/` | Incremental ANSI/VT + UTF-8 state machine; `ParserHost` callback interface for responses/title/clipboard/bell/modes. |
| `cpp/engine/screen/` | Cell grid, cursor, scroll region, scrollback (depth from `Config`), alt screen, wide chars, **combining marks** (2 per base), tab stops, damage, selection, search, viewport, SGR→ARGB via `Config`, `applyConfig`/`cellGrapheme`. |
| `cpp/engine/charset/` | `wcwidth`-style Unicode width tables (CJK/emoji=2, combining=0). |
| `cpp/include/xterm/` | Public `Types.h` (Cell + combining slots + attribute flags) and `Config.h` (theme + scrollback: single source of truth, index→ARGB resolution). |
| `cpp/engine/terminal/` | Thread-safe facade over parser + screen; `configure()`/`cursorColor()`/`cellGrapheme()` for theme + grapheme. |
| `cpp/engine/session/` | PTY ↔ Terminal wiring + reader thread + generation. |
| `cpp/core/logger/` | Android log macros. |
| `java/.../NativeTerminal.kt` | JNI binding. |
| `java/.../Bootstrap.kt` | env, symlinks, busybox applets, default/proot commands. |
| `java/.../TerminalSession.kt` | Handle lifecycle + key encoding (`TermKeys`). |
| `java/.../TerminalView.kt` | Grid renderer + input. |
| `java/.../ExtraKeysView.kt` | Bottom extra-keys toolbar. |
| `java/.../MainActivity.kt` | Wiring + WindowInsets/IME handling. |

## Directory layout

The C++ tree carries **only modules that contain real, compiled code** — the empty
skeleton folders from the initial scaffold (`engine/{ansi,buffer,cursor,emulator,history,
renderer,shell,api}`, `core/{config,event,thread,timer,memory}`, `platform/*`, `utils`,
`third_party`) were **removed** rather than kept as empty contracts, because each mandated
capability already lives in a populated module:

- ANSI/VT emulation → `engine/parser` + `engine/terminal`
- buffer / history / cursor → `engine/screen` (grid, bounded scrollback, cursor state)
- charset / width / grapheme → `engine/charset` + `engine/screen` (combining marks)
- config / theme → `include/xterm/Config.h` (consumed by `engine/screen`)
- shell / process → `engine/pty` + `engine/session` + `Bootstrap.kt`
- renderer → the Android side (`TerminalView.onDraw`); the native side emits snapshots
- JNI / platform → `native-lib.cpp`

Rendering stays on the Kotlin `Canvas`; the native engine is renderer-agnostic and only
produces flat snapshot buffers.


## RootFS Provisioning Engine (Kotlin, `com.dracxterm.rootfs`)

`ProvisioningActivity` is the launcher. On first run it drives a single-responsibility
pipeline and only then hands off to `MainActivity` (the terminal):

`ProvisioningActivity` → `BootManager` → `RootfsDiscovery` → `RootfsValidator` →
`RootfsExtractor` → `RootfsConfigurator` → `RuntimeValidator`, with `ProvisioningState`
persisting progress.

- **RootfsDiscovery** — lists `assets/rootfs/` and picks the archive matching the device ABI.
- **RootfsValidator** — magic-byte + free-space checks.
- **RootfsExtractor** — streams asset → xz/gzip/plain → tar into a `.tmp` dir with a tar-slip
  guard, symlink/hardlink/mode preservation, device-node skipping, then atomically renames to
  `filesDir/rootfs` (crash-safe). Uses commons-compress + tukaani-xz.
- **RootfsConfigurator** — creates PRoot bind mountpoints, `resolv.conf`/`hosts`, HOME, profile.
- **ProvisioningState** — SharedPreferences + `rootfs/.provisioned` marker → provision only on
  first run or after invalidation; subsequent launches skip straight to the terminal.
- **RuntimeValidator** — fast skip-vs-provision check + full pre-launch validation.
- **BootManager** — orchestrates and returns LINUX / BUSYBOX / ERROR. No archive bundled →
  BUSYBOX mode (a working terminal, not an error).

Integration: the extractor populates `filesDir/rootfs`; the existing `Bootstrap` already
auto-selects PRoot when that path holds a shell, so no engine change was needed. The official
(and only) bundled-image location is `app/src/main/assets/rootfs/`.
