package com.dracxterm.rootfs

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Provisioning State (SRP: persistence).
 * Records how far provisioning got so we only re-run it on first launch or after an
 * invalidation. Backed by SharedPreferences plus an on-disk marker inside the rootfs.
 */
class ProvisioningState(private val ctx: Context) {

    enum class Stage { NONE, VALIDATED, EXTRACTING, CONFIGURING, VERIFYING, DONE, FAILED }

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val marker = File(ctx.filesDir, "rootfs/.provisioned")

    var stage: Stage
        get() = runCatching { Stage.valueOf(prefs.getString(KEY_STAGE, Stage.NONE.name)!!) }
            .getOrDefault(Stage.NONE)
        set(v) { prefs.edit().putString(KEY_STAGE, v.name).apply() }

    fun markDone(archiveName: String) {
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText("provisioned:$archiveName:${System.currentTimeMillis()}")
        }
        stage = Stage.DONE
    }

    fun markFailed(reason: String) {
        Log.w(TAG, "provisioning failed: $reason")
        stage = Stage.FAILED
    }

    /** True only when a previous run fully completed provisioning. */
    fun isProvisioned(): Boolean = marker.exists() && stage == Stage.DONE

    private companion object {
        const val TAG = "Provisioning"
        const val PREFS = "provisioning"
        const val KEY_STAGE = "stage"
    }
}
