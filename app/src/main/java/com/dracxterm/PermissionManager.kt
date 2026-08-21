package com.dracxterm

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Runtime permission policy, API-level aware.
 *
 * Design rules:
 *  - INTERNET / ACCESS_NETWORK_STATE are NORMAL permissions: declared in the manifest and
 *    granted at install, so they are never requested at runtime.
 *  - Shared-storage access is requested with the RIGHT permission for the OS version:
 *      • API 33+  -> granular READ_MEDIA_IMAGES / VIDEO / AUDIO
 *      • API 23..32 -> READ_EXTERNAL_STORAGE (legacy)
 *      • < 23     -> nothing to request (install-time grant)
 *  - "All files access" (MANAGE_EXTERNAL_STORAGE) is a SPECIAL access handled separately and
 *    only ever opt-in via Settings; the terminal is fully usable without it.
 *
 * Denial is never fatal: callers boot the terminal regardless. Storage integration simply
 * degrades to app-private storage when broad access is not granted.
 */
object PermissionManager {

    /** Runtime permissions appropriate to this OS version (may be empty). */
    fun runtimePermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_AUDIO
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        )
        else -> emptyArray()
    }

    /** Subset of [runtimePermissions] not yet granted. Empty => nothing to ask for. */
    fun missingRuntime(ctx: Context): Array<String> =
        runtimePermissions().filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    /** True when the app holds broad ("all files") filesystem access. */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * Intent that opens the system "All files access" screen for this app, or null when the OS
     * predates scoped storage (nothing to grant). The caller decides whether to surface it.
     */
    fun allFilesAccessIntent(ctx: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${ctx.packageName}")
        )
    }

    // ---------------- Storage Access (Settings ▸ Storage Access; adapts termux-setup-storage) ----

    /**
     * Legacy read/write runtime permissions to request on API 23..29, where WRITE_EXTERNAL_STORAGE
     * still grants broad path access (manifest caps WRITE at 29, READ at 32). Empty elsewhere:
     * API 30+ uses All-files access instead, < 23 grants at install.
     */
    fun legacyStoragePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.Q)
            arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        else emptyArray()

    /**
     * True when the app can actually reach shared storage by path (what ~/storage needs):
     *  - API 30+ : "all files access" (MANAGE_EXTERNAL_STORAGE) is held,
     *  - API 23..29 : READ_EXTERNAL_STORAGE is granted,
     *  - < 23 : granted at install.
     */
    fun storageGranted(ctx: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        else -> true
    }

    /** One-line, human-readable storage state for the Settings screen. */
    fun storageStatusText(ctx: Context): String = when {
        storageGranted(ctx) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> "granted (all files access)"
        storageGranted(ctx) -> "granted"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> "not granted — needs all-files access"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> "not granted — needs storage permission"
        else -> "granted at install"
    }
}
