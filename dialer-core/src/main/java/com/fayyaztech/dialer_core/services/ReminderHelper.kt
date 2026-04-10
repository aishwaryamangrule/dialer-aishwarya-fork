package com.fayyaztech.dialer_core.services

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fayyaztech.dialer_core.ui.dialer.DialerActivity

/**
 * Schedules and cancels "call-back reminder" alarms triggered when the user taps
 * "Remind me later" on an incoming call screen.
 *
 * ## Flow
 * 1. User taps "Remind me later" → `schedule(context, number, delayMs)` is called after
 *    `Call.reject()`.
 * 2. At the chosen time the system delivers the alarm to [ReminderReceiver].
 * 3. [ReminderReceiver] posts a high-priority "Call back [number]" notification with a
 *    direct-call action.
 *
 * ## Alarm accuracy
 * - API 31+ (S): uses `setExactAndAllowWhileIdle` (requires SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM).
 * - API 23–30: uses `setExactAndAllowWhileIdle`.
 * - Older: falls back to `setExact`.
 */
object ReminderHelper {

    private const val TAG            = "ReminderHelper"
    private const val REQUEST_CODE   = 9901
    const val EXTRA_PHONE_NUMBER     = "reminder_phone_number"
    const val EXTRA_CONTACT_NAME     = "reminder_contact_name"

    /**
     * Schedules a callback reminder [delayMs] milliseconds from now.
     *
     * @param context     Any context.
     * @param phoneNumber The caller's phone number to dial back.
     * @param contactName Display name of the caller (may be null; falls back to [phoneNumber]).
     * @param delayMs     Delay in milliseconds (e.g. 5 * 60 * 1000L for 5 minutes).
     */
    fun schedule(
        context: Context,
        phoneNumber: String,
        contactName: String?,
        delayMs: Long,
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context, phoneNumber, contactName)
        val triggerAt = System.currentTimeMillis() + delayMs

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            Log.d(TAG, "Reminder scheduled for $phoneNumber in ${delayMs / 1000}s")
        } catch (e: SecurityException) {
            // SCHEDULE_EXACT_ALARM not granted (API 31 Samsung / Huawei etc.) — fall back
            Log.w(TAG, "Exact alarm denied, using inexact fallback: ${e.message}")
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** Cancels any pending reminder for [phoneNumber]. */
    fun cancel(context: Context, phoneNumber: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(buildPendingIntent(context, phoneNumber, null))
        Log.d(TAG, "Reminder cancelled for $phoneNumber")
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun buildPendingIntent(
        context: Context,
        phoneNumber: String,
        contactName: String?,
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
            putExtra(EXTRA_CONTACT_NAME, contactName)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

// =============================================================================
// ReminderReceiver — fires when AlarmManager delivers the reminder
// =============================================================================

/**
 * Receives the callback reminder alarm and posts a notification with a "Call back" action.
 *
 * Registered in `AndroidManifest.xml` with `android:exported="false"`.
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG              = "ReminderReceiver"
        private const val CHANNEL_ID       = "callback_reminder_channel"
        private const val NOTIFICATION_ID  = 9902
    }

    override fun onReceive(context: Context, intent: Intent) {
        val phoneNumber  = intent.getStringExtra(ReminderHelper.EXTRA_PHONE_NUMBER) ?: return
        val contactName  = intent.getStringExtra(ReminderHelper.EXTRA_CONTACT_NAME)
        val displayName  = if (!contactName.isNullOrBlank()) contactName else phoneNumber

        Log.d(TAG, "Reminder fired for $displayName ($phoneNumber)")

        ensureChannel(context)

        // Route via DialerActivity so SIM preference / ask-each-time / roaming policy are applied.
        val callIntent = Intent(context, DialerActivity::class.java).apply {
            putExtra("PHONE_NUMBER", phoneNumber)
            putExtra(DialerActivity.EXTRA_AUTO_PLACE_CALL, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val callPi = PendingIntent.getActivity(
            context, NOTIFICATION_ID, callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Call back reminder")
            .setContentText("Call back $displayName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(callPi)
            .addAction(android.R.drawable.sym_action_call, "Call now", callPi)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Callback Reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Reminders to call back missed incoming calls"
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }
}
