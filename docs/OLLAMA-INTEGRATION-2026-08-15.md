# drac-Xterm — Ollama ARM64 Integration Report

**Date:** 2026-08-15
**Pinned release:** `v0.32.14-rc0` (official pre-release, never silently substituted)
**Artifact:** `ollama-linux-arm64.tar.zst`

---

## 0. Verification labels used in this document

| Label | Meaning |
|---|---|
| **SOURCE VERIFIED** | Read directly from project source or from the Ollama repo at the pinned tag. |
| **HOST VERIFIED** | Executed and measured on the analysis host (real download, real ELF, real extraction, real compile). |
| **BUILD** | `./gradlew assembleDebug` — **BLOCKED**. |
| **DEVICE-VERIFY** | Requires the Android device. Not claimed as verified. |

**Nothing in this document is fabricated.** Every number is either measured or cited to a file and line.

---

## 1. PROVISIONING DECISION

```
SELECTED STRATEGY: STREAM + DROP ACCELERATOR RUNNERS
```

Per the decision hierarchy (§16 of your directive): CUDA was **proven unnecessary**, so branch 1 applies.

`PRE-SLIM ON LAPTOP` was **rejected**. It was never necessary: the app provisions the official
artifact deterministically by itself, and a laptop-built package would add an external build
dependency, version drift, and checksum ambiguity for zero technical gain — exactly the costs your
§6 listed. `FULL EXTRACTION` was **rejected**: it would install 2.08 GiB of NVIDIA datacenter GPU
libraries that cannot load on an Android handset.

---

## 2. CUDA ANALYSIS — the proof

```
CUDA v12: NOT REQUIRED
CUDA v13: NOT REQUIRED
Loading behaviour: DYNAMIC / LAZY (filesystem-discovered, dlopen'd), never eager
```

### 2.1 The split is structural, not nominal

**SOURCE VERIFIED** — `CMakeLists.txt` (ollama, pinned tag):

```cmake
set(OLLAMA_LIB_DIR     "lib/ollama" CACHE STRING "Install destination for Ollama runtime payloads")
set(OLLAMA_INSTALL_DIR ${OLLAMA_LIB_DIR}/${OLLAMA_RUNNER_DIR})
```

**SOURCE VERIFIED** — `llama/server/CMakePresets.json`, the complete set of `OLLAMA_RUNNER_DIR` values:

```
""  (CPU)  |  cuda_v12  |  cuda_v13  |  cuda_jetpack5  |  cuda_jetpack6
           |  rocm_v7_1 |  rocm_v7_2 |  vulkan
```

The CPU runtime is the **only** runner installed *flat* into `lib/ollama/`. Every hardware
accelerator gets its **own subdirectory**. That is why the filter in `OllamaPayloadPolicy.kt` is
**directory-shaped** and not `if (name.contains("cuda")) drop`.

### 2.2 Discovery is a filesystem glob, and absence is a first-class supported state

**SOURCE VERIFIED** — `discover/runner.go:48` and `:56`:

```go
files, err := filepath.Glob(filepath.Join(ml.LibOllamaPath, "*", "*ggml-*"))
for _, file := range files { libDirs[filepath.Dir(file)] = struct{}{} }
...
if len(libDirs) == 0 { libDirs[""] = struct{}{} }
```

and the `dir == ""` branch runs discovery with `dirs = []string{ml.LibOllamaPath}` — the CPU base
directory alone. **No accelerator subdirectory ⇒ zero glob matches ⇒ the documented zero-GPU code
path.** Not an error, not a degraded fallback: the normal path on a machine without a GPU.

**SOURCE VERIFIED** — `ml/path.go:16-18`, verbatim comment:

> `// LibOllamaPath is the root used to find bundled llama.cpp and MLX runtime`
> `// libraries. GPU-specific libraries live in backend subdirectories such as`
> `// cuda_v12, rocm_v7_2, vulkan, and mlx_cuda_v13.`

### 2.3 The dependency arrow points one way only

**SOURCE VERIFIED** — `llm/llama_server.go:500-503`, verbatim:

```go
// Library path ordering:
// 1. llama-server's own directory — ggml-base, ggml-cpu, libllama
// 2. GPU variant directories — cublas, cudart, backend DLL/.so
// 3. User/system library path
```

Layer 2 is **appended on top of** layer 1. Layer 1 is the shared base every execution path needs;
layer 2 is additive and only for *discovered* devices (`ml/device.go:LibraryPaths` seeds the list
with `LibOllamaPath` and appends per-device dirs). **Removing layer 2 cannot remove anything layer 1
depends on.** That is the shared-dependency question in your §3-C answered directionally, not by
guesswork.

### 2.4 Nothing in the kept set links against CUDA — HOST VERIFIED by `readelf`

```
bin/ollama                DT_NEEDED: libresolv.so.2  libdl.so.2  libpthread.so.0
                                     libstdc++.so.6  libc.so.6

lib/ollama/llama-server   DT_NEEDED: libllama-server-impl.so  libllama-common.so.0
                                     libmtmd.so.0  libllama.so.0  libggml.so.0
                                     libggml-base.so.0  libpthread.so.0  libstdc++.so.6
                                     libm.so.6  libgcc_s.so.1  libc.so.6
                          RUNPATH:   $ORIGIN
```

**Not one `DT_NEEDED` entry across the entire kept set names cuda, cublas, cudart or nvidia.**
`libcudart.so`, `libnvidia-ml.so`, `libggml-cuda.so` and the regex `^libcudart\.so\.(\d+)...`
appear in `bin/ollama` **only as string literals** used for runtime dlopen/probing. That is the
eager-vs-dynamic question in your §3-D settled at the ELF level.

### 2.5 The hardware cannot exist

`ggml-cuda` requires the NVIDIA **driver** (`libcuda.so.1`), which is *not in the archive* and is
never present on an Android handset. The dropped libraries could not load even if installed.

---

## 3. STORAGE — all values MEASURED (HOST VERIFIED), none estimated

| Metric | Value |
|---|---|
| Original archive size (on the wire) | **1,542,906,646 B** (1,471.4 MiB) |
| Archive SHA-256 | `45b01ab0…57bd43` — matches official `sha256sum.txt` **and** the GitHub asset `digest` |
| Total uncompressed payload | **2,236,699,784 B** (2,133.1 MiB) |
| **Final installed runtime size** | **59,076,136 B (56.3 MiB)**, 31 entries |
| **Savings (storage avoided)** | **2,177,623,648 B (2.03 GiB)** — **97.36 %** of the payload |
| Temporary peak storage | ~1.60 GB (verified artifact 1.44 GiB + 56 MiB staging), both deleted after promotion |
| **CUDA files removed** | **14** — `cuda_v12` (7), `cuda_v13` (7) |
| **Other files removed** | **0** |
| Model storage location | `/home/dracos/.ollama/models` → host `filesDir/guest-home/.ollama/models` |

### Full measured composition

| Group | Bytes | MiB | Entries | Decision |
|---|---:|---:|---:|---|
| `lib/ollama/cuda_v12/` | 1,241,376,000 | 1,183.9 | 7 | **DROP** |
| `lib/ollama/cuda_v13/` | 936,247,648 | 892.9 | 7 | **DROP** |
| `bin/` | 35,792,104 | 34.1 | 1 | KEEP |
| `lib/ollama/` (CPU base) | 23,284,032 | 22.2 | 30 | KEEP |

The kept CPU base is: `libggml-base`, 8 × `libggml-cpu-armv8.0/8.2/8.6/9.2` micro-architecture
variants, `libggml`, `libgomp`, `libomp`, `libllama`, `libllama-common`, `libllama-server-impl`,
`libllama-quantize-impl`, `libmtmd`, `llama-server`, `llama-quantize`.

> **Note on the pipeline order.** Your §8 mandates *download → verify → inspect/extract*. The
> installer follows it exactly: the artifact is written to `.download.tmp` while SHA-256 is computed
> in the same pass, the checksum is verified, and **only then** is a single byte of it parsed. This
> costs ~1.44 GiB of temporary space (pre-checked before any download starts) rather than the ~56 MiB
> a verify-while-extracting design would use. Integrity was ranked above storage, as instructed.
> There is no second full copy: extraction writes straight into staging and promotion is a `rename`.

---

## 4. ZSTD DECODER DECISION

```
ZSTD DECODER DECISION: OPTION 1 — zstd-jni Android AAR (com.github.luben:zstd-jni:1.5.7-13@aar)

REASON:  The only decoder that satisfies all criteria without an architectural dependency cycle.
         Option 3 (decompress inside the Kali guest) was REJECTED on the cycle test you specified:
         provisioning Ollama must be possible while the guest lacks zstd AND curl (Kali nano ships
         neither guaranteed), so "need Ollama -> need Kali -> need zstd -> need network tooling in
         the guest" is exactly the circular dependency §7 says to reject. Option 2 (pure-Java) was
         not needed once Option 1 passed every criterion; it would add a second dependency with a
         less-exercised code path on a 1.44 GiB stream for no benefit.

ANDROID:          PASS  — official Android AAR, minSdk 21 (this module is 24), empty
                          AndroidManifest.xml so nothing merges into the app manifest.
ARM64:            PASS  — built via externalNativeBuild/CMake; AGP default builds all four ABIs
                          into the AAR, and this module's `abiFilters += "arm64-v8a"` packages only
                          the arm64-v8a .so. No new architecture enters the APK.
STREAMING:        PASS  — `ZstdInputStream extends FilterInputStream` (SOURCE VERIFIED in the
                          zstd-jni tree).
BOUNDED MEMORY:   PASS  — fixed internal source buffer `srcBuffSize = recommendedDInSize()`
                          (ZSTD_DStreamInSize, ~128 KiB). The 1.44 GiB archive is never held in RAM
                          and never fully decompressed to a `.tar` on disk.
KALI DEPENDENCY:  NO
NATIVE DEPENDENCY: YES  — one arm64-v8a .so from the AAR.
LICENSE:          BSD 2-Clause (verified: repo LICENSE + `licenses := Seq("BSD 2-Clause License"…)`
                          in build.sbt).
FINAL APK IMPACT: AAR published size 946,419 B for ALL FOUR ABIs plus classes.
                  Per-ABI arm64-v8a slice: NOT MEASURED — Maven Central is unreachable from the
                  analysis sandbox (proxy 403), so the AAR could not be opened. DEVICE-VERIFY.
REGRESSION:       DEVICE-VERIFY (see §8).
```

**Layer separation (§11) is enforced:** `ZstdInputStream` decodes *only* the Zstandard frame;
`TarArchiveInputStream` (already in the project) owns archive entries; `OllamaPayloadPolicy` owns
the keep/drop decision. Three files, three responsibilities.

The decoder is **not** exposed as a terminal command, and the terminal parser, PTY, renderer,
keyboard and UI are untouched (§9).

---

## 5. RUNTIME ARCHITECTURE

```
Android (app process, FOREGROUND_SERVICE_SPECIAL_USE keeps it alive)
  ↓
drac-Xterm  ── TerminalSession.start → Bootstrap.prepare → NativeTerminal.nativeCreate
  ↓
Terminal PTY  (forkpty, C++ engine — UNTOUCHED)
  ↓
PRoot fake-root  ── libproot.so -0 --link2symlink -r filesDir/rootfs
                    -b /dev -b /proc -b /sys -b guest-home:/home/dracos -w /home/dracos
  ↓
Kali ARM64 rootfs  ── bash -l   (glibc)
  ↓
PATH lookup: /usr/local/sbin:/usr/local/bin:…   ← the EXISTING mechanism, unmodified
  ↓
/usr/local/bin/ollama            ← launcher (shell), installed like the existing `xset` command
  ↓  validates runtime, exports config, ensures a server, then exec
/opt/ollama/v0.32.14-rc0/bin/ollama          ← the REAL official binary
  ↓  LibOllamaPath = dirname(EvalSymlinks(exe))/../lib/ollama
/opt/ollama/v0.32.14-rc0/lib/ollama/llama-server + libggml-cpu-* (CPU inference)
```

```
Architecture:  ARM64 (aarch64)         — HOST VERIFIED via ELF e_machine
Runtime:       PRoot → Kali Linux rootfs (glibc)
CPU:           CPU-only. No CUDA, no NVIDIA runtime, no GPU emulation, no fake libraries.
```

**HOST VERIFIED — the ELF equivalent of `file ollama`:**

```
bin/ollama: ELF 64-bit LSB pie executable, ARM aarch64, version 1 (SYSV), dynamically linked,
            interpreter /lib/ld-linux-aarch64.so.1, for GNU/Linux 3.7.0, stripped
```

`/lib/ld-linux-aarch64.so.1` is the **glibc** loader. Android uses Bionic. This is the definitive
answer to §4/§19: **Ollama cannot run on the BusyBox/Android host under any configuration** — it
must live in the Linux rootfs, which is where it is installed. Maximum symbol versions required:
`GLIBC_2.28`, `GLIBCXX_3.4.22`, `CXXABI_1.3.11` (build base is `almalinux:8`). Kali rolling ships
glibc ≥ 2.36 and GCC 12 (`GLIBCXX_3.4.30`), so the requirement is satisfied **provided
`libstdc++6`/`libgcc-s1` are installed** — the launcher preflights this and prints an actionable
`apt install` line rather than failing obscurely.

---

## 6. INSTALLATION PATHS

| What | Guest path | Host path |
|---|---|---|
| Binary | `/opt/ollama/v0.32.14-rc0/bin/ollama` | `filesDir/rootfs/opt/ollama/v0.32.14-rc0/bin/ollama` |
| Runtime libraries | `/opt/ollama/v0.32.14-rc0/lib/ollama/` | …`/lib/ollama/` |
| Launcher on PATH | `/usr/local/bin/ollama` | `filesDir/rootfs/usr/local/bin/ollama` |
| Install metadata | `/opt/ollama/v0.32.14-rc0/.install.json` | …`/.install.json` |
| **Models** | `/home/dracos/.ollama/models` | `filesDir/guest-home/.ollama/models` |
| Runtime state / logs | `/home/dracos/.ollama/{install.state,install.log,server.log,server.pid}` | `filesDir/guest-home/.ollama/…` |
| Staging (transient) | `/opt/ollama/.stage-v0.32.14-rc0` | swept on next attempt |
| Download temp (transient) | `/opt/ollama/.download.tmp` | deleted in `finally` |

**Why inside the rootfs rather than an app-private dir + PRoot bind:** installing into the rootfs
requires **zero change** to `Bootstrap.prootArgv` — no new bind, no new mountpoint, no change to the
env array. That bind list is spawn-fixed and is the single most regression-prone area of this
codebase (the comment at `Bootstrap.kt:188-207` documents a real, already-fixed bug caused by
nested-bind `stat` behaviour under proot 5.1.0, plus a second caused by gating a bind on a
start-time permission snapshot). Storage accounting is identical — `filesDir/rootfs` is already
app-private. Lower risk, equal benefit.

**Models deliberately live outside the rootfs**, in the bound `guest-home`, so they survive a rootfs
re-provision and are shared by every workspace. `OLLAMA_MODELS` is pinned explicitly rather than
left to its `$HOME/.ollama/models` default, because the existing `sudo` shim sets `HOME=/root` —
without the pin, models would silently relocate under `sudo`.

---

## 7. CHANGED FILES

### Added (7 — all isolated, nothing pre-existing depends on them)

| File | Why | Regression risk |
|---|---|---|
| `app/src/main/java/com/dracxterm/ollama/OllamaConfig.kt` | Single source of truth: pinned tag, checksum, paths, env. §13 "do not scatter environment variables". | None — new package, pure constants. |
| `app/src/main/java/com/dracxterm/ollama/OllamaPayloadPolicy.kt` | Explicit allow/deny + tar-slip/symlink safety gate. | None — pure function, no I/O. |
| `app/src/main/java/com/dracxterm/ollama/OllamaState.kt` | Crash-safe, idempotent, version-aware install state; readiness re-derived from disk. | None — writes only under `opt/ollama` and `guest-home/.ollama`. |
| `app/src/main/java/com/dracxterm/ollama/OllamaInstaller.kt` | download → verify → stream zstd/tar → filter → validate → atomic promote. | None — touches only its own staging/prefix dirs. |
| `app/src/main/java/com/dracxterm/ollama/OllamaLauncher.kt` | Installs the guest command; arms the on-demand provisioning watcher. | Only new writes: `rootfs/usr/local/bin/ollama` (a new filename) and `rootfs/opt/`. |
| `app/src/main/assets/ollama/launcher.sh` | The `ollama` command inside the Linux rootfs. | New asset folder; `assets/compat/` untouched. |
| `app/src/main/assets/ollama/unavailable.sh` | §19 no-rootfs case: explicit, actionable, never fake success. | New asset. |

### Modified (2 — 9 and 12 added lines, zero lines changed or deleted)

**`app/src/main/java/com/dracxterm/Bootstrap.kt`** — one import + one call, inserted after the
existing `hasGuest` computation:

```kotlin
+ import com.dracxterm.ollama.OllamaLauncher
...
  val hasGuest = hasRootfs(rootfs)
+ OllamaLauncher.attach(ctx, if (hasGuest) File(rootfs) else null, usrBin)
```

*Why it changed:* the `ollama` command must land on the PATH the session will actually use, and only
`Bootstrap` knows whether this session is PRoot or BusyBox. *Why it is isolated:* it directly mirrors
the existing `installXsetCommand(ctx, usrBin)` on line 51 — same "refresh every launch" contract,
same non-fatal discipline. `attach()` wraps everything in `runCatching`, so a failure logs and the
terminal boots exactly as before. **No existing line was modified.** `prootArgv`, the env arrays, the
bind list, the shell selection, storage handling and the compat shims are byte-identical.

**`app/build.gradle.kts`** — one dependency added. `compileSdk`, `targetSdk`, `minSdk`, `abiFilters`,
`packaging`, `signingConfigs`, `buildTypes`, `externalNativeBuild`, `androidResources` and every
existing dependency are **byte-identical**. No Gradle/AGP/Kotlin/NDK version was touched.

### Not modified — verified byte-identical

`TerminalSession.kt`, `TerminalView.kt`, `MainActivity.kt`, `NativeTerminal.kt`, `ExtraKeysView.kt`,
`WorkspaceManager.kt`, `WorkspaceSwipeContainer.kt`, `TerminalService.kt`, `PermissionManager.kt`,
all of `cpp/**` (PTY, ANSI parser, screen buffer, JNI, terminal engine), all of `rootfs/**`, all of
`xset/**`, `AndroidManifest.xml`, `jniLibs/**`, `assets/compat/**`, `assets/bashrc`, `assets/banner*`,
`res/**`, root `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`.

**No permission was added.** `INTERNET` + `ACCESS_NETWORK_STATE` already exist (`AndroidManifest.xml:5-6`).

---

## 8. VERIFICATION

### HOST VERIFIED (actually executed — real output, nothing fabricated)

**Artifact integrity**

```
$ sha256sum art.tar.zst
45b01ab0ed4b1079a80a9287d0346951cff15935de61d00663c4afc83e57bd43  art.tar.zst

official sha256sum.txt line   : 45b01ab0…57bd43  ./ollama-linux-arm64.tar.zst
GitHub asset "digest" field   : sha256:45b01ab0…57bd43
size                          : 1,542,906,646 B  (matches the release metadata exactly)
```

**ELF architecture (§16)**

```
bin/ollama:               ELF 64-bit LSB pie executable, ARM aarch64, interpreter
                          /lib/ld-linux-aarch64.so.1, for GNU/Linux 3.7.0, stripped
lib/ollama/llama-server:  ELF 64-bit LSB executable, ARM aarch64, RUNPATH $ORIGIN
```

**Filter correctness — the shipped `OllamaPayloadPolicy` compiled and run over the real 47-entry manifest**

```
KEEP   entries=31  bytes=59,076,136
DROP   entries=14  bytes=2,177,623,648   runners=[cuda_v12, cuda_v13]
REJECT entries=0

bin/ollama kept?        true
llama-server kept?      true
libggml-cpu-* kept?     8
any cuda path kept?     []
```

Byte-for-byte identical to an independent measurement of the same manifest. Adversarial cases:

```
../../../../data/data/com.dracxterm/files/x     -> Reject (path traversal)
/etc/passwd                                     -> Reject (absolute path)
bin/evil -> ../../../../system/bin/sh           -> Reject (link escapes root)
lib/ollama/link -> /etc/shadow                  -> Reject (absolute link target)
lib/ollama/cuda_v99/libggml-cuda.so             -> DropAccelerator(cuda_v99)
lib/ollama/rocm_v9_9/x.so                       -> DropAccelerator(rocm_v9_9)
lib/ollama/npu_future/x.so                      -> Keep  (unrecognised -> conservative)
share/doc/README                                -> Keep  (unrecognised -> conservative)
```

**Launcher behaviour (the exact shipped script, tokens substituted)**

```
sh -n / dash -n                     : OK (both)
not installed, non-interactive      : refuses the download, exit 69          PASS
recursion guard                     : "recursion detected", exit 70          PASS
exit-code forwarding                : binary exits 42 -> launcher exits 42   PASS
env at exec                          : OLLAMA_HOST + OLLAMA_MODELS set;
                                       OLLAMA_TMPDIR unset; LD_LIBRARY_PATH unset   PASS
`--version` does NOT start a server                                          PASS
two concurrent invocations          : exactly 1 `serve`, lock released       PASS
```

That last line is the multi-workspace duplicate-server guarantee (§20), demonstrated rather than asserted.

**Compilation**

```
$ /opt/kotlinc/bin/kotlinc -jvm-target 17 -d out stubs src cs      # Kotlin 1.9.24, jvmTarget 17
EXIT=0
```

All five new Kotlin files, plus a call site mirroring the exact line added to `Bootstrap.prepare()`,
type-check under the project's **exact** Kotlin version and JVM target — against hand-written API
stubs for `android.*`, `org.json`, commons-compress and zstd-jni, **not** the real Android SDK.

### BLOCKED

```
./gradlew assembleDebug                      : BLOCKED
```

No Android SDK/NDK is present in this analysis sandbox, and I have no shell on your laptop (the
device bridge exposes file transfer only). I did not run it and I do not claim it.

### DEVICE-VERIFY — required before this can be called done

```
which ollama                : DEVICE-VERIFY
ollama --version            : DEVICE-VERIFY
ollama                      : DEVICE-VERIFY
Runtime validation          : DEVICE-VERIFY
```

Also DEVICE-VERIFY: whether the Kali nethunter nano image ships `libstdc++6`/`libgcc-s1` (the
launcher preflight reports this explicitly), real decompression throughput, real peak RAM, real
install wall-clock, the arm64-v8a slice size of the zstd-jni AAR in the built APK, and PRoot ptrace
overhead on a Go + pthreads workload.

**Run this on the device, in order:**

```sh
which ollama                 # expect /usr/local/bin/ollama
ollama --version             # first run prompts to download; should print the ollama version
cat /opt/ollama/v0.32.14-rc0/.install.json
ollama                       # expect the real interactive TUI
```

Then the regression set: `pwd`, `ls`, `cd`, `echo hello`, `whoami`, `id`, `uname -a`, `apt --version`,
workspace switch 1↔2, keyboard open/close, toolbar, scrolling, exit, relaunch.

---

## 9. REGRESSION REPORT

**I did not run the app, so I do not report PASS for anything.** Per your instruction — *"Only report
PASS when actually tested"* — the honest status is:

```
Terminal        : NOT TESTED (DEVICE-VERIFY)   — source byte-identical
PTY             : NOT TESTED (DEVICE-VERIFY)   — source byte-identical (cpp/engine/pty untouched)
Renderer        : NOT TESTED (DEVICE-VERIFY)   — source byte-identical (TerminalView.kt untouched)
Keyboard        : NOT TESTED (DEVICE-VERIFY)   — source byte-identical
Toolbar         : NOT TESTED (DEVICE-VERIFY)   — source byte-identical (ExtraKeysView.kt untouched)
Workspace       : NOT TESTED (DEVICE-VERIFY)   — source byte-identical (WorkspaceManager.kt untouched)
Rootfs          : NOT TESTED (DEVICE-VERIFY)   — source byte-identical (rootfs/** untouched)
Existing shell  : NOT TESTED (DEVICE-VERIFY)   — Bootstrap argv/env/binds byte-identical
Ollama          : ADDED as an isolated runtime capability
```

What **is** established, structurally: the entire change set is **2 modified files, +21 added lines,
0 modified lines, 0 deleted lines**, and the one line inside `Bootstrap` is a `runCatching`-wrapped
call that returns `Unit` and cannot alter `argv`, `env`, `cwd` or the launch path. The single
plausible regression channel is that `OllamaLauncher.attach` runs synchronously on the session-start
path and does a small amount of file I/O (writing one ~7 KB script, two `mkdirs`) — the same class
and order of work `installXsetCommand` and `installCompatShims` already do on that thread.

---

## 10. WHAT REMAINS

1. **Build it:** `./gradlew assembleDebug` on your Aspire. First build fetches
   `com.github.luben:zstd-jni:1.5.7-13@aar`.
2. **Confirm the APK stayed arm64-only:** `unzip -l app-debug.apk | grep '\.so$'` — expect only
   `lib/arm64-v8a/…`, now including `libzstd-jni-*.so`.
3. **Run the DEVICE-VERIFY list in §8.**
4. Report back what `ollama --version` actually prints, and I will close out the verification table.

---

## 11. SOURCE-OF-TRUTH GAPS FOUND DURING THE AUDIT

- **`STATUS.md` does not exist** in the connected folder, though your brief lists it as authoritative.
- **`FINAL-POST-IMPLEMENTATION-AUDIT-2026-08-11.md` does not exist** either.
- **No `.git` directory**, so the §23 "Git/source diff" baseline was impossible; a size+mtime
  manifest of all 89 tracked files was captured instead, before any edit.
- **`docs/ARCHITECTURE.md:113` and `Bootstrap.kt:19` are stale.** Both claim no rootfs is bundled;
  `assets/rootfs/kali-nethunter-rootfs-nano-arm64.tar.xz` (197 MiB) is present. Per your rule
  (SOURCE WINS), PRoot + Kali is the real default runtime. I did **not** edit those comments — that
  would be unrelated cleanup, which your §22 forbids.
