package com.fayyaztech.dialer_core.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Detects the device OEM/brand and provides safe, intent-based deep-links to
 * OEM-specific auto-start / background-permission settings screens.
 *
 * No OEM-private APIs are used — all intents fall back to the standard
 * application-details screen if the target component is not found on the device.
 */
object OemCompatibilityHelper {

    private const val TAG = "OemCompatibilityHelper"

    // ── Brand detection ───────────────────────────────────────────────────────

    private val manufacturer: String
        get() = Build.MANUFACTURER.lowercase().trim()

    val isXiaomiOrMiui: Boolean
        get() = manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
                runCatching { Class.forName("miui.os.Build"); true }.getOrDefault(false)

    val isHuawei: Boolean
        get() = manufacturer.contains("huawei") || manufacturer.contains("honor")

    val isOppo: Boolean
        get() = manufacturer.contains("oppo")

    val isVivo: Boolean
        get() = manufacturer.contains("vivo")

    val isOnePlus: Boolean
        get() = manufacturer.contains("oneplus") || manufacturer.contains("one plus")

    val isRealme: Boolean
        get() = manufacturer.contains("realme")

    val isSamsung: Boolean
        get() = manufacturer.contains("samsung")

    /** True if this device belongs to any OEM known to restrict background starts. */
    val isRestrictiveOem: Boolean
        get() = isXiaomiOrMiui || isHuawei || isOppo || isVivo || isOnePlus || isRealme

    // ── Auto-start settings ───────────────────────────────────────────────────

    /**
     * Opens the OEM-specific auto-start / permission-manager settings page for this app.
     * Falls back to the standard app-details screen when the OEM page is unavailable.
     *
     * Returns true if the OEM-specific screen was launched, false if the fallback was used.
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val oemIntent = buildAutoStartIntent(context)
        if (oemIntent != null && context.packageManager.resolveActivity(oemIntent, 0) != null) {
            runCatching {
                context.startActivity(oemIntent.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                Log.d(TAG, "Opened OEM auto-start settings for $manufacturer")
                return true
            }.onFailure { Log.w(TAG, "OEM intent failed: ${it.message}") }
        }
        // Fallback: standard app details
        openAppDetailsSettings(context)
        return false
    }

    private fun buildAutoStartIntent(context: Context): Intent? = when {
        isXiaomiOrMiui -> Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
        )
        isHuawei -> Intent().setComponent(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            )
        )
        isOppo -> Intent().setComponent(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            )
        ).let { base ->
            // Some OPPO/ColorOS versions use a different package
            if (context.packageManager.resolveActivity(base, 0) != null) base
            else Intent().setComponent(
                ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity",
                )
            )
        }
        isVivo -> Intent().setComponent(
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            )
        )
        isOnePlus -> Intent().setComponent(
            ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            )
        )
        isRealme -> Intent().setComponent(
            ComponentName(
                "com.realmepowermanager",
                "com.realmepowermanager.RealmePowerManagerActivity",
            )
        )
        else -> null
    }

    private fun openAppDetailsSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure { Log.e(TAG, "Could not open app details: ${it.message}") }
    }

    // ── Auto-start guidance dialog state ─────────────────────────────────────

    private const val PREFS_NAME = "oem_compat_prefs"
    private const val KEY_SHOWN_AUTOSTART = "shown_autostart_dialog"

    fun hasShownAutoStartDialog(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOWN_AUTOSTART, false)

    fun markAutoStartDialogShown(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOWN_AUTOSTART, true).apply()
    }
}
