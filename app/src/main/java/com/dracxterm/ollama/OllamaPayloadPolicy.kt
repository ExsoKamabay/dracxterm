package com.dracxterm.ollama

/**
 * EXPLICIT allow/deny policy for entries of the official `ollama-linux-arm64.tar.zst`.
 *
 * The directive is emphatic that this must NOT be `if (name.contains("cuda")) drop`. It is not.
 * The policy below mirrors the upstream INSTALL LAYOUT CONTRACT, which is defined in exactly one
 * place in the Ollama sources:
 *
 *     CMakeLists.txt:
 *         set(OLLAMA_LIB_DIR     "lib/ollama" ...)
 *         set(OLLAMA_INSTALL_DIR ${OLLAMA_LIB_DIR}/${OLLAMA_RUNNER_DIR})
 *
 *     llama/server/CMakePresets.json — the complete set of OLLAMA_RUNNER_DIR values:
 *         ""  (CPU)  | cuda_v12 | cuda_v13 | cuda_jetpack5 | cuda_jetpack6
 *                    | rocm_v7_1 | rocm_v7_2 | vulkan
 *
 * So: the CPU runtime is the ONLY runner installed FLAT into `lib/ollama/`. Every hardware
 * accelerator runner is installed into its own `lib/ollama/<runner>/` SUBDIRECTORY. The split is
 * structural, not nominal — which is why this filter is directory-shaped.
 *
 * WHY THE ACCELERATOR SUBDIRECTORIES ARE PROVABLY OPTIONAL (all four independent proofs):
 *
 *  1. DISCOVERY IS A FILESYSTEM GLOB, AND ABSENCE IS A SUPPORTED STATE.
 *     discover/runner.go:48
 *         files, err := filepath.Glob(filepath.Join(ml.LibOllamaPath, "*", "*ggml-*"))
 *         for _, file := range files { libDirs[filepath.Dir(file)] = struct{}{} }
 *     discover/runner.go:56
 *         if len(libDirs) == 0 { libDirs[""] = struct{}{} }
 *     ...and the dir=="" branch runs discovery with `dirs = []string{ml.LibOllamaPath}` — the CPU
 *     base directory alone. No accelerator subdirectory => zero glob matches => the documented
 *     zero-GPU code path. Not an error; not a fallback; the normal path on a machine with no GPU.
 *
 *  2. THE DEPENDENCY ARROW POINTS ONE WAY ONLY.
 *     llm/llama_server.go:500-503, verbatim:
 *         // Library path ordering:
 *         // 1. llama-server's own directory — ggml-base, ggml-cpu, libllama
 *         // 2. GPU variant directories — cublas, cudart, backend DLL/.so
 *         // 3. User/system library path
 *     Layer 2 is APPENDED ON TOP of layer 1. Layer 1 is the shared base every path needs; layer 2
 *     is additive and per-discovered-device. Removing layer 2 cannot remove anything layer 1 needs.
 *
 *  3. NOTHING IN THE KEPT SET LINKS AGAINST CUDA.  (HOST VERIFIED by readelf on the real artifact.)
 *     bin/ollama            DT_NEEDED: libresolv.so.2, libdl.so.2, libpthread.so.0,
 *                                      libstdc++.so.6, libc.so.6
 *     lib/ollama/llama-server DT_NEEDED: libllama-server-impl.so, libllama-common.so.0,
 *                                      libmtmd.so.0, libllama.so.0, libggml.so.0,
 *                                      libggml-base.so.0, libpthread.so.0, libstdc++.so.6,
 *                                      libm.so.6, libgcc_s.so.1, libc.so.6  | RUNPATH: $ORIGIN
 *     Not one DT_NEEDED entry across the entire kept set names cuda, cublas, cudart or nvidia.
 *     `libcudart.so`, `libnvidia-ml.so` and `libggml-cuda.so` appear ONLY as string literals used
 *     for runtime dlopen/regex probing => loading is LAZY AND DYNAMIC, never eager.
 *
 *  4. THE HARDWARE CANNOT EXIST. ggml-cuda needs the NVIDIA *driver* (libcuda.so.1), which is not
 *     in the archive and is never present on an Android handset. The libraries could not load even
 *     if installed.
 *
 * CONSERVATISM RULE (directive §5, "do not implement blind filtering"): an entry is dropped ONLY
 * when it sits inside a subdirectory this file explicitly recognises as an accelerator runner. An
 * UNRECOGNISED subdirectory under lib/ollama, or any unrecognised top-level path, is KEPT and
 * logged — a future upstream layout change costs storage, never correctness.
 *
 * FUTURE GPU SUPPORT (directive §12): [Decision.DropAccelerator] carries the runner name, so a
 * later build can flip individual runners to KEEP without touching the extractor. No speculative
 * code is added now.
 */
object OllamaPayloadPolicy {

    sealed class Decision {
        /** Required for CPU-only ARM64 execution. */
        data class Keep(val why: String) : Decision()
        /** Proven-unnecessary hardware-accelerator runner payload. */
        data class DropAccelerator(val runner: String, val why: String) : Decision()
        /** Refused for safety (tar-slip, absolute path, escaping symlink). Fails the install. */
        data class Reject(val why: String) : Decision()
    }

    /** Exact OLLAMA_RUNNER_DIR values that denote a hardware accelerator runner. Source:
     *  llama/server/CMakePresets.json at tag v0.32.14-rc0. */
    private val ACCELERATOR_RUNNERS = setOf(
        "cuda_v12", "cuda_v13",
        "cuda_jetpack5", "cuda_jetpack6",
        "rocm_v7_1", "rocm_v7_2",
        "vulkan"
    )

    /** Prefixes covering versioned accelerator runners upstream may add (cuda_v14, rocm_v8_0,
     *  mlx_cuda_v13 — the last is named in ml/path.go's own comment). Still a DIRECTORY test on a
     *  runner-dir name, never a substring test on a file name. */
    private val ACCELERATOR_PREFIXES = listOf("cuda_", "rocm_", "hip_", "mlx_", "metal_")

    private fun isAcceleratorRunner(dir: String): Boolean =
        dir in ACCELERATOR_RUNNERS || ACCELERATOR_PREFIXES.any { dir.startsWith(it) }

    /**
     * Classify one tar entry path (already normalised to forward slashes, no leading "./").
     * [linkTarget] is the symlink/hardlink target when the entry is a link, else null.
     */
    fun classify(path: String, linkTarget: String?): Decision {
        // ---- Safety gate first: never let archive metadata decide where bytes land (§14/§21) ----
        if (path.isEmpty()) return Decision.Reject("empty entry name")
        if (path.startsWith("/")) return Decision.Reject("absolute path in archive: $path")
        if (path.split('/').any { it == ".." }) return Decision.Reject("path traversal in archive: $path")
        if (linkTarget != null) {
            if (linkTarget.startsWith("/")) return Decision.Reject("absolute link target: $path -> $linkTarget")
            if (escapes(path, linkTarget)) return Decision.Reject("link escapes archive root: $path -> $linkTarget")
        }

        val parts = path.split('/')

        // ---- ALLOW: the Ollama CLI/server executable ------------------------------------------
        if (parts.size == 2 && parts[0] == "bin") {
            return Decision.Keep("ollama executable (bin/)")
        }

        if (parts.size >= 3 && parts[0] == "lib" && parts[1] == "ollama") {
            // depth-1 under lib/ollama  => OLLAMA_RUNNER_DIR == "" => the CPU/base runtime.
            if (parts.size == 3) {
                return Decision.Keep("CPU/base runtime (lib/ollama, OLLAMA_RUNNER_DIR=\"\")")
            }
            // deeper => lib/ollama/<runner>/... => a runner subdirectory.
            val runner = parts[2]
            return if (isAcceleratorRunner(runner)) {
                Decision.DropAccelerator(
                    runner,
                    "hardware-accelerator runner dir; dynamically discovered via " +
                        "filepath.Glob(lib/ollama/*/*ggml-*) and additive to the CPU base, " +
                        "and its NVIDIA/AMD driver can never exist on Android"
                )
            } else {
                Decision.Keep("unrecognised lib/ollama subdirectory '$runner' - kept (conservative)")
            }
        }

        // ---- Anything else: keep and log. Never drop what we have not proven unnecessary. -------
        return Decision.Keep("unrecognised archive path - kept (conservative)")
    }

    /** True if resolving [target] relative to the directory of [path] climbs above the archive root. */
    private fun escapes(path: String, target: String): Boolean {
        val base = path.split('/').dropLast(1).toMutableList()
        for (seg in target.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> { if (base.isEmpty()) return true; base.removeAt(base.size - 1) }
                else -> base.add(seg)
            }
        }
        return false
    }
}
