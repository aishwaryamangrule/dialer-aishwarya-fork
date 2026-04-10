package com.fayyaztech.dialer_core.services

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Helpers for checking and requesting battery-optimization exemption.
 *
 * Background: Android's Doze mode and OEM battery-savers can kill the dialer's
 * foreground service and alarm delivery, causing missed call reminders.
 * [REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] allows the app to request exemption
 * directly (requires the matching `<uses-permission>` in the manifest).
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptimizationHelper"
    private const val PREFS_NAME = "battery_opt_prefs"
    private const val KEY_SHOWN_DIALOG = "shown_exemption_dialog"

    // ── State ─────────────────────────────────────────────────────────────────

    /** Returns true if the app is already whitelisted for battery optimization. */
    fun isIgnoringOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Returns true if we have already shown the exemption dialog to the user. */
    fun hasShownExemptionDialog(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOWN_DIALOG, false)

    /** Marks that the exemption dialog has been shown so we don't show it again. */
    fun markExemptionDialogShown(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOWN_DIALOG, true).apply()
    }

    // ── Request ───────────────────────────────────────────────────────────────

    /**
     * Launches the system dialog that asks the user to exempt this app from
     * battery optimization. Requires `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
     * permission in the manifest.
     *
     * On API < 23 this is a no-op (Doze does not exist).
     */
    fun requestExemption(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "Battery exemption request launched")
        }.onFailure {
            // Fallback: open the general battery settings page
            Log.w(TAG, "Direct exemption intent failed, opening battery settings: ${it.message}")
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }.onFailure { e -> Log.e(TAG, "Battery settings fallback also failed: ${e.message}") }
        }
    }
}
