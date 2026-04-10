package com.fayyaztech.dialer_core.services

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * Handles USSD (Unstructured Supplementary Service Data) code dispatch.
 *
 * USSD codes are strings that start with `*` and end with `#` (e.g. `*100#`, `*#06#`).
 * They are sent to the carrier network and return a text response in a dialog.
 *
 * **Requires**: [android.Manifest.permission.CALL_PHONE], Android 8.0+ (API 26).
 */
object UssdHelper {

    private const val TAG = "UssdHelper"

    /**
     * Returns `true` when [number] looks like a USSD code.
     * A valid USSD code starts with `*` (or `#`) and ends with `#`.
     */
    fun isUssdCode(number: String): Boolean =
        number.length >= 3 && (number.startsWith("*") || number.startsWith("#")) && number.endsWith("#")

    /**
     * Sends a USSD request via [TelephonyManager.sendUssdRequest].
     *
     * Requires [android.Manifest.permission.CALL_PHONE] and Android 8.0+ (API 26).
     * The [onResult] / [onError] callbacks are invoked on the main thread.
     *
     * @param context        Context with CALL_PHONE permission.
     * @param code           The USSD code to send (e.g. `"*100#"`).
     * @param subscriptionId The telephony subscription ID to send on; `-1` uses the default.
     * @param onResult       Called with the carrier's response string on success.
     * @param onError        Called with a human-readable failure message on error.
     */
    fun sendUssdCode(
        context: Context,
        code: String,
        subscriptionId: Int,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onError("USSD requires Android 8.0 or later")
            return
        }

        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("CALL_PHONE permission is required to send USSD codes")
            return
        }

        val baseTm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (baseTm == null) {
            onError("Telephony service is not available on this device")
            return
        }

        val tm = if (subscriptionId >= 0) {
            try { baseTm.createForSubscriptionId(subscriptionId) } catch (_: Exception) { baseTm }
        } else {
            baseTm
        }

        val callback = object : TelephonyManager.UssdResponseCallback() {
            override fun onReceiveUssdResponse(
                telephonyManager: TelephonyManager,
                request: String,
                response: CharSequence,
            ) {
                Log.d(TAG, "USSD response for '$request': $response")
                onResult(response.toString())
            }

            override fun onReceiveUssdResponseFailed(
                telephonyManager: TelephonyManager,
                request: String,
                failureCode: Int,
            ) {
                val msg = when (failureCode) {
                    TelephonyManager.USSD_RETURN_FAILURE          -> "Network returned a failure response"
                    TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL   -> "USSD service is not available"
                    else                                           -> "USSD failed (code $failureCode)"
                }
                Log.w(TAG, "USSD failed for '$request': $msg")
                onError(msg)
            }
        }

        try {
            @Suppress("MissingPermission")
            tm.sendUssdRequest(code, callback, Handler(context.mainLooper))
            Log.d(TAG, "USSD sent: '$code' on subscriptionId=$subscriptionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send USSD code '$code': ${e.message}", e)
            onError("Failed to send USSD: ${e.message ?: "unknown error"}")
        }
    }
}
