package com.fayyaztech.dialer_core.services

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Queries the current Do-Not-Disturb and power-save state so callers can adapt
 * notification strategy and alarm scheduling accordingly.
 *
 * No OEM-private APIs are used — only standard Android [NotificationManager] and
 * [PowerManager] APIs are queried.
 */
object PowerPolicyHelper {

    private const val TAG = "PowerPolicyHelper"

    // ── DND / Interruption filter ─────────────────────────────────────────────

    /**
     * Returns true if Do-Not-Disturb is currently suppressing all or most notifications.
     *
     * Requires [NotificationManager.isNotificationPolicyAccessGranted] to read
     * the current filter. If policy access is not granted, this returns false
     * (i.e., we optimistically assume DND is not blocking us).
     */
    fun isDndActive(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return try {
            if (!nm.isNotificationPolicyAccessGranted) {
                Log.d(TAG, "DND policy access not granted — assuming DND inactive")
                return false
            }
            val filter = nm.currentInterruptionFilter
            Log.d(TAG, "Current interruption filter: $filter")
            filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
                    filter == NotificationManager.INTERRUPTION_FILTER_ALARMS
        } catch (e: Exception) {
            Log.w(TAG, "isDndActive error: ${e.message}")
            false
        }
    }

    /**
     * Returns true if the system is in Battery Saver / power-save mode.
     * On API < 21 always returns false.
     */
    fun isPowerSaveMode(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isPowerSaveMode.also { Log.d(TAG, "isPowerSaveMode=$it") }
    }

    // ── Composite recommendation ──────────────────────────────────────────────

    /**
     * Encapsulates the current policy state in a single data class so callers
     * can make a single query and branch on any combination of flags.
     */
    data class PolicyState(
        val dndActive: Boolean,
        val powerSaveActive: Boolean,
    ) {
        /** True when any policy is restricting background activity or notifications. */
        val isRestricted: Boolean get() = dndActive || powerSaveActive
    }

    fun getPolicyState(context: Context) = PolicyState(
        dndActive       = isDndActive(context),
        powerSaveActive = isPowerSaveMode(context),
    )
}
