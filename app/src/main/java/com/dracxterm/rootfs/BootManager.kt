package com.dracxterm.rootfs

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Boot Manager (SRP: orchestration).
 * Wires the discovery -> validation -> extraction -> configuration -> verification pipeline
 * and decides how to boot:
 *   - rootfs already valid           -> LINUX  (provisioning skipped)
 *   - bundled archive present        -> provision, then LINUX
 *   - no archive & no rootfs         -> BUSYBOX (a working terminal; not an error)
 *   - archive present but fails       -> ERROR  (halt with a clear reason)
 * Runs on the calling (background) thread; UI updates flow through [Listener].
 */
class BootManager(private val ctx: Context) {

    enum class BootMode { LINUX, BUSYBOX, ERROR }
    data class Outcome(val mode: BootMode, val message: String)

    fun interface Listener {
        fun onStage(stage: ProvisioningState.Stage, message: String, percent: Int) // percent<0 => indeterminate
    }

    private val discovery = RootfsDiscovery(ctx)
    private val validator = RootfsValidator(ctx)
    private val extractor = RootfsExtractor(ctx)
    private val configurator = RootfsConfigurator()
    private val runtime = RuntimeValidator(ctx)
    private val state = ProvisioningState(ctx)
    private val rootfs = File(ctx.filesDir, "rootfs")

    fun boot(listener: Listener): Outcome {
        Log.i(ShellLocator.TAG, "[BOOT] BootManager.boot() start; rootfs=${rootfs.absolutePath}")
        // 1. Already provisioned and still valid -> straight to Linux.
        if (state.isProvisioned() && runtime.isRootfsReady()) {
            runRecovery(listener)
            listener.onStage(ProvisioningState.Stage.DONE, "Linux environment ready", 100)
            return Outcome(BootMode.LINUX, "reused existing rootfs")
        }

        // 2. Discover a bundled image.
        listener.onStage(ProvisioningState.Stage.NONE, "Looking for a Linux image…", 3)
        val archive = discovery.select()
        if (archive == null) {
            if (runtime.isBusyboxReady()) {
                listener.onStage(ProvisioningState.Stage.DONE, "No Linux image bundled — starting BusyBox shell", 100)
                return Outcome(BootMode.BUSYBOX, "busybox mode")
            }
            return Outcome(BootMode.ERROR, "No Linux image and BusyBox is missing")
        }

        // 3. Validate integrity + capacity.
        listener.onStage(ProvisioningState.Stage.VALIDATED, "Validating ${archive.fileName}…", 8)
        when (val v = validator.validate(archive, rootfs)) {
            is RootfsValidator.Result.Invalid -> { state.markFailed(v.reason); return Outcome(BootMode.ERROR, v.reason) }
            RootfsValidator.Result.Ok -> {}
        }

        // 4. Extract (indeterminate; the entry count is unknown while streaming).
        state.stage = ProvisioningState.Stage.EXTRACTING
        val ext = extractor.extract(archive, rootfs) { entries, _, _ ->
            listener.onStage(ProvisioningState.Stage.EXTRACTING, "Extracting… $entries files", -1)
        }
        if (ext is RootfsExtractor.Result.Failed) { state.markFailed(ext.reason); return Outcome(BootMode.ERROR, ext.reason) }

        // 5. Configure.
        listener.onStage(ProvisioningState.Stage.CONFIGURING, "Configuring environment…", 92)
        when (val c = configurator.configure(rootfs)) {
            is RootfsConfigurator.Result.Failed -> { state.markFailed(c.reason); return Outcome(BootMode.ERROR, c.reason) }
            RootfsConfigurator.Result.Ok -> {}
        }

        // 6. Verify the installed environment can actually run.
        listener.onStage(ProvisioningState.Stage.VERIFYING, "Verifying installation…", 97)
        val report = runtime.validate()
        if (!report.ok) { state.markFailed(report.detail); return Outcome(BootMode.ERROR, report.detail) }

        state.markDone(archive.fileName)
        runRecovery(listener)
        listener.onStage(ProvisioningState.Stage.DONE, "Linux ready", 100)
        return Outcome(BootMode.LINUX, "provisioned ${archive.fileName}")
    }

    /** Repair apt/dpkg ownership + interrupted state before handing off to Linux. Marker-gated,
     *  best-effort, never fatal (a recovery failure must not block a working terminal). */
    private fun runRecovery(listener: Listener) {
        listener.onStage(ProvisioningState.Stage.VERIFYING, "Finalizing package manager…", -1)
        runCatching { DpkgRecoveryEngine.run(ctx, rootfs) }
            .onFailure { Log.w(ShellLocator.TAG, "[BOOT] recovery skipped: ${it.message}") }
    }
}
