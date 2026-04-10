package com.fayyaztech.dialer_core.services

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.ContactsContract
import android.util.Log

/**
 * Plays the appropriate ringtone for an incoming call using the following priority cascade:
 *
 * 1. **Contact ringtone** — the `CUSTOM_RINGTONE` column set per-contact in the system
 *    contacts database (`ContactsContract.Contacts.CUSTOM_RINGTONE`).
 * 2. **SIM ringtone** — a per-subscription-ID ringtone stored in SharedPreferences by this
 *    helper (user-selectable via a settings screen).
 * 3. **System default** — `RingtoneManager.getDefaultUri(TYPE_RINGTONE)`.
 *
 * Group ringtones are not part of the standard Android contacts schema; per-contact ringtone
 * is the finest-grained standard option.
 *
 * ## Usage (from DefaultInCallService)
 * ```kotlin
 * // When STATE_RINGING:
 * RingtoneHelper.startRinging(this, phoneNumber, subscriptionId)
 *
 * // When STATE_ACTIVE or call removed:
 * RingtoneHelper.stopRinging()
 * ```
 */
object RingtoneHelper {

    private const val TAG        = "RingtoneHelper"
    private const val PREFS_NAME = "dialer_ringtones"
    private const val KEY_SIM    = "sim_ringtone_"

    private var activeRingtone: Ringtone? = null
    private var activeVibrator: Vibrator? = null
    private var isRinging = false

    // Vibration pattern: wait 0ms, vibrate 400ms, pause 600ms, repeat
    private val VIBRATION_PATTERN = longArrayOf(0L, 400L, 600L)

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Resolves and starts the appropriate ringtone + vibration for an incoming call.
     * Safe to call from any thread.
     *
     * @param context        Service / Activity context.
     * @param phoneNumber    Caller's phone number (used for contact CUSTOM_RINGTONE lookup).
     * @param subscriptionId The telephony subscription ID of the receiving SIM (−1 if unknown).
     */
    fun startRinging(context: Context, phoneNumber: String, subscriptionId: String?) {
        stopRinging() // ensure no previous ringtone is playing

        val ringtoneUri = resolveRingtoneUri(context, phoneNumber, subscriptionId)
        Log.d(TAG, "Starting ringtone: $ringtoneUri")

        try {
            val ringtone = RingtoneManager.getRingtone(context, ringtoneUri) ?: run {
                Log.w(TAG, "RingtoneManager returned null — falling back to default")
                RingtoneManager.getRingtone(
                    context,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                )
            }
            ringtone?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    it.isLooping = true
                }
                it.audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                it.play()
                activeRingtone = it
                isRinging = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play ringtone: ${e.message}")
        }

        // Vibration
        startVibration(context)
    }

    /** Stops the currently playing ringtone and vibration. Safe to call multiple times. */
    fun stopRinging() {
        try {
            activeRingtone?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop ringtone: ${e.message}")
        } finally {
            activeRingtone = null
        }
        stopVibration()
        isRinging = false
        Log.d(TAG, "Ringtone stopped")
    }

    // ── SIM ringtone settings ──────────────────────────────────────────────────

    /**
     * Sets a custom ringtone URI for a specific SIM [subscriptionId].
     * Pass `null` to clear the SIM-specific override (fall back to system default).
     */
    fun setSimRingtone(context: Context, subscriptionId: Int, uri: Uri?) {
        prefs(context).edit().apply {
            if (uri == null) remove(KEY_SIM + subscriptionId)
            else putString(KEY_SIM + subscriptionId, uri.toString())
            apply()
        }
        Log.d(TAG, "SIM ringtone set: sub=$subscriptionId → $uri")
    }

    /** Returns the custom SIM ringtone URI, or `null` if not set. */
    fun getSimRingtone(context: Context, subscriptionId: Int): Uri? {
        val raw = prefs(context).getString(KEY_SIM + subscriptionId, null) ?: return null
        return try { Uri.parse(raw) } catch (_: Exception) { null }
    }

    // ── Resolution ─────────────────────────────────────────────────────────────

    private fun resolveRingtoneUri(
        context: Context,
        phoneNumber: String,
        subscriptionId: String?,
    ): Uri {
        // 1. Contact custom ringtone
        val contactRingtone = getContactRingtone(context, phoneNumber)
        if (contactRingtone != null) {
            Log.d(TAG, "Using contact ringtone: $contactRingtone")
            return contactRingtone
        }

        // 2. SIM-specific ringtone
        val subId = subscriptionId?.toIntOrNull() ?: -1
        if (subId >= 0) {
            val simRingtone = getSimRingtone(context, subId)
            if (simRingtone != null) {
                Log.d(TAG, "Using SIM ringtone for subId=$subId: $simRingtone")
                return simRingtone
            }
        }

        // 3. System default
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        Log.d(TAG, "Using system default ringtone: $default")
        return default
    }

    /**
     * Queries [ContactsContract] for the `CUSTOM_RINGTONE` column associated with [phoneNumber].
     * Returns `null` if not found or if READ_CONTACTS is not granted.
     */
    private fun getContactRingtone(context: Context, phoneNumber: String): Uri? {
        if (android.content.pm.PackageManager.PERMISSION_GRANTED !=
            androidx.core.app.ActivityCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_CONTACTS,
            )
        ) return null

        return try {
            val lookupUri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI
                .buildUpon()
                .appendPath(phoneNumber)
                .build()

            val projection = arrayOf(
                ContactsContract.PhoneLookup.CONTACT_ID,
                ContactsContract.PhoneLookup.LOOKUP_KEY,
            )
            context.contentResolver.query(lookupUri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val contactId = cursor.getLong(0)
                val lookupKey = cursor.getString(1)

                val contactUri = ContactsContract.Contacts.getLookupUri(contactId, lookupKey)
                context.contentResolver.query(
                    contactUri,
                    arrayOf(ContactsContract.Contacts.CUSTOM_RINGTONE),
                    null, null, null,
                )?.use { c2 ->
                    if (c2.moveToFirst()) {
                        val raw = c2.getString(0) ?: return null
                        if (raw.isBlank()) null else Uri.parse(raw)
                    } else null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getContactRingtone failed: ${e.message}")
            null
        }
    }

    // ── Vibration ──────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun startVibration(context: Context) {
        try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            activeVibrator = vibrator

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(VIBRATION_PATTERN, /* repeat= */ 0),
                )
            } else {
                vibrator.vibrate(VIBRATION_PATTERN, /* repeat= */ 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startVibration failed: ${e.message}")
        }
    }

    private fun stopVibration() {
        try {
            activeVibrator?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "stopVibration failed: ${e.message}")
        } finally {
            activeVibrator = null
        }
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
