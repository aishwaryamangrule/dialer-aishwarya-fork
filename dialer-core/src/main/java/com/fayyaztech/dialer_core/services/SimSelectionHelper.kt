package com.fayyaztech.dialer_core.services

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * Helper that queries the device's active SIM subscriptions and maps them to
 * [PhoneAccountHandle] objects usable with [TelecomManager.placeCall].
 *
 * Usage (from a screen / ViewModel):
 *
 * ```kotlin
 * val sims = SimSelectionHelper.getAvailableSims(context)
 * // Show picker → user picks sims[0] or sims[1]
 * SimSelectionHelper.placeCall(context, "tel:+10000000000", sims[0].phoneAccountHandle)
 * ```
 */
object SimSelectionHelper {

    private const val TAG = "SimSelectionHelper"

    // -------------------------------------------------------------------------
    // Data model
    // -------------------------------------------------------------------------

    /**
     * Lightweight descriptor for a single active SIM slot.
     *
     * @param slotIndex     Physical SIM slot index (0-based).
     * @param subscriptionId The telephony subscription ID.
     * @param displayName   Human-readable name (operator name or user label).
     * @param phoneNumber   The SIM's phone number when READ_PHONE_NUMBERS is granted
     *                       (may be blank on some carriers).
     * @param phoneAccountHandle The [PhoneAccountHandle] needed by [TelecomManager.placeCall].
     */
    data class SimInfo(
        val slotIndex: Int,
        val subscriptionId: Int,
        val displayName: String,
        val phoneNumber: String,
        val phoneAccountHandle: PhoneAccountHandle?
    )

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns a list of available/active SIM card info objects.
     *
     * Requires [android.Manifest.permission.READ_PHONE_STATE] (always) and
     * [android.Manifest.permission.READ_PHONE_NUMBERS] (for the phone number on API 30+).
     * Falls back gracefully if permissions are not granted.
     *
     * @param context Any context; must not be null.
     * @return Ordered list of active SIMs (index 0 = slot 1, etc.). Empty if no SIMs or
     *         READ_PHONE_STATE is denied.
     */
    fun getAvailableSims(context: Context): List<SimInfo> {
        if (!hasReadPhoneStatePermission(context)) {
            Log.w(TAG, "READ_PHONE_STATE not granted — cannot query SIM subscriptions")
            return emptyList()
        }

        val subscriptionManager =
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                ?: return emptyList()

        val telecomManager =
            context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return emptyList()

        val activeSubscriptions: List<SubscriptionInfo> = try {
            @Suppress("MissingPermission")
            subscriptionManager.activeSubscriptionInfoList ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get active subscription list: ${e.message}")
            emptyList()
        }

        if (activeSubscriptions.isEmpty()) {
            Log.d(TAG, "No active subscriptions found")
            return emptyList()
        }

        // Map every active subscription → callable PhoneAccountHandle
        val callCapableAccounts: List<PhoneAccountHandle> = try {
            @Suppress("MissingPermission")
            telecomManager.callCapablePhoneAccounts ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get call-capable phone accounts: ${e.message}")
            emptyList()
        }

        return activeSubscriptions.mapIndexed { index, sub ->
            val displayName = sub.displayName?.toString()
                ?: sub.carrierName?.toString()
                ?: "SIM ${index + 1}"

            val phoneNumber = resolvePhoneNumber(context, subscriptionManager, sub)

            // Match the SubscriptionInfo to a PhoneAccountHandle by comparing subscription IDs
            // embedded in the account ID string (standard AOSP format).
            val handle = matchPhoneAccountHandle(callCapableAccounts, telecomManager, sub.subscriptionId)

            SimInfo(
                slotIndex = sub.simSlotIndex,
                subscriptionId = sub.subscriptionId,
                displayName = displayName,
                phoneNumber = phoneNumber,
                phoneAccountHandle = handle
            ).also {
                Log.d(TAG, "SIM found: slot=${it.slotIndex}, id=${it.subscriptionId}, name=$displayName, number=$phoneNumber, handle=$handle")
            }
        }
    }

    /**
     * Places an outgoing call on the specified SIM.
     *
     * @param context   A context with CALL_PHONE permission.
     * @param number    Phone number to dial (will be formatted as a `tel:` URI if needed).
     * @param handle    The [PhoneAccountHandle] returned from [getAvailableSims].
     *                  Pass `null` to let the system choose the default SIM.
     */
    fun placeCall(context: Context, number: String, handle: PhoneAccountHandle?) {
        if (!hasCallPhonePermission(context)) {
            Log.e(TAG, "CALL_PHONE permission not granted — cannot place call")
            return
        }

        val telecomManager =
            context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return

        val uri: Uri = if (number.startsWith("tel:")) {
            Uri.parse(number)
        } else {
            Uri.parse("tel:${number.replace(" ", "").replace("-", "")}")
        }

        val extras = android.os.Bundle()
        if (handle != null) {
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        }

        try {
            @Suppress("MissingPermission")
            telecomManager.placeCall(uri, extras)
            Log.d(TAG, "placeCall → uri=$uri, handle=$handle")
        } catch (e: Exception) {
            Log.e(TAG, "placeCall failed: ${e.message}", e)
        }
    }

    /**
     * Returns `true` when the mobile network currently serving [subscriptionId] is a roaming
     * network (i.e. the device is outside its home network).
     *
     * Falls back to `false` when [android.Manifest.permission.READ_PHONE_STATE] is not granted
     * or when the subscription is invalid.
     *
     * @param subscriptionId The telephony subscription ID to check; `-1` uses the default.
     */
    fun isRoaming(context: Context, subscriptionId: Int): Boolean {
        if (!hasReadPhoneStatePermission(context)) return false
        return try {
            val baseTm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return false
            val tm = if (subscriptionId >= 0) baseTm.createForSubscriptionId(subscriptionId) else baseTm
            @Suppress("MissingPermission")
            tm.isNetworkRoaming
        } catch (e: Exception) {
            Log.w(TAG, "isRoaming check failed for subscriptionId=$subscriptionId: ${e.message}")
            false
        }
    }

    /**
     * Returns the [PhoneAccountHandle] for the system's current default outgoing SIM,
     * or `null` if no default is set.
     */
    fun getDefaultOutgoingSimHandle(context: Context): PhoneAccountHandle? {
        val telecomManager =
            context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return null
        return try {
            @Suppress("MissingPermission")
            telecomManager.userSelectedOutgoingPhoneAccount
        } catch (e: Exception) {
            Log.w(TAG, "Could not read default outgoing phone account: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the phone number for a subscription, respecting runtime permissions.
     * On API 30+ the READ_PHONE_NUMBERS permission is required.
     */
    private fun resolvePhoneNumber(
        context: Context,
        subscriptionManager: SubscriptionManager,
        sub: SubscriptionInfo
    ): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+: READ_PHONE_NUMBERS required
                if (hasReadPhoneNumbersPermission(context)) {
                    @Suppress("MissingPermission")
                    subscriptionManager.getPhoneNumber(sub.subscriptionId).ifEmpty { "" }
                } else {
                    ""
                }
            } else {
                // Pre-API 30: number lives in SubscriptionInfo
                @Suppress("DEPRECATION")
                sub.number ?: ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read phone number for sub ${sub.subscriptionId}: ${e.message}")
            ""
        }
    }

    /**
     * Matches a list of [PhoneAccountHandle]s to the given subscription ID.
     *
     * The standard AOSP telephony component stores the subscription ID as the
     * account ID string (e.g. "1", "2"). We try numeric comparison first,
     * then a TelecomManager lookup as fallback.
     */
    private fun matchPhoneAccountHandle(
        handles: List<PhoneAccountHandle>,
        telecomManager: TelecomManager,
        subscriptionId: Int
    ): PhoneAccountHandle? {
        for (handle in handles) {
            try {
                // Try direct numeric match on the account ID
                if (handle.id.toIntOrNull() == subscriptionId) return handle

                // Try reading the PhoneAccount and checking subscriptionId in extras
                val account = telecomManager.getPhoneAccount(handle)
                val extras = account?.extras
                if (extras != null) {
                    val subIdFromExtras =
                        extras.getInt("android.telecom.extra.SUB_ID", Int.MIN_VALUE)
                    if (subIdFromExtras == subscriptionId) return handle
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inspect handle ${handle.id}: ${e.message}")
            }
        }
        Log.d(TAG, "No exact handle match for subscriptionId=$subscriptionId — will return null (system default)")
        return null
    }

    private fun hasReadPhoneStatePermission(context: Context): Boolean =
        ActivityCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasReadPhoneNumbersPermission(context: Context): Boolean =
        ActivityCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_NUMBERS
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasCallPhonePermission(context: Context): Boolean =
        ActivityCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
}
