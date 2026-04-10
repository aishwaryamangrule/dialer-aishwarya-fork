package com.fayyaztech.dialer_core.services

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * Helper for detecting the current network type and IMS (IP Multimedia Subsystem) capabilities —
 * specifically VoLTE (Voice over LTE) and VoWiFi (Wi-Fi Calling).
 *
 * ### What this helper can and cannot do
 * - **Can detect**: Whether the device/carrier currently has VoLTE or VoWiFi available for a
 *   given subscription.
 * - **Cannot enable**: VoLTE and VoWiFi are carrier/provisioning features. A third-party dialer
 *   cannot enable them; it can only read their availability status and display that to the user.
 *
 * ### API level notes
 * - `TelephonyManager.isVoLteAvailable()` requires API 26 minimum but is available without
 *   READ_PRECISE_PHONE_STATE on API < 31.
 * - `ImsMmTelManager` (API 29+) is the modern way to query IMS feature availability. On API < 29
 *   we fall back to `TelephonyManager.isVoLteAvailable()` / `isWifiCallingAvailable()`.
 */
object ImsStatusHelper {

    private const val TAG = "ImsStatusHelper"

    // -------------------------------------------------------------------------
    // Network type enum
    // -------------------------------------------------------------------------

    /**
     * Describes the effective network type and call technology in use.
     *
     * [UNKNOWN] is returned when the permission is denied or the API is unavailable.
     */
    enum class CallNetworkType {
        /** Cannot determine — permission denied or API not available. */
        UNKNOWN,
        /** 2G/3G circuit-switched voice. */
        GSM_WCDMA,
        /** Voice over LTE. */
        VoLTE,
        /** Wi-Fi Calling / Voice over Wi-Fi. */
        VoWiFi,
        /** 5G NR voice (VoNR). */
        VoNR
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the [CallNetworkType] for the default subscription.
     *
     * Requires [android.Manifest.permission.READ_PHONE_STATE] on all API levels.
     * On API 31+ also requires [android.Manifest.permission.READ_PRECISE_PHONE_STATE].
     *
     * @param context Any context.
     * @return Best-effort network type for the current/most-recent call.
     */
    fun getCallNetworkType(context: Context): CallNetworkType {
        if (!hasReadPhoneStatePermission(context)) {
            Log.w(TAG, "READ_PHONE_STATE not granted — cannot determine call network type")
            return CallNetworkType.UNKNOWN
        }

        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return CallNetworkType.UNKNOWN

        // IMS-based query (API 29+) is more reliable than the network type int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return getCallNetworkTypeViaIms(context, telephonyManager)
        }

        // Fallback for API 24-28: use TelephonyManager voice network type
        return getCallNetworkTypeLegacy(telephonyManager)
    }

    /**
     * Returns `true` if VoLTE is currently available on the default subscription.
     *
     * @param context Any context.
     */
    fun isVoLteAvailable(context: Context): Boolean =
        getCallNetworkType(context) == CallNetworkType.VoLTE ||
                isVoLteAvailableDirect(context)

    /**
     * Returns `true` if Wi-Fi Calling (VoWiFi) is currently available on the default subscription.
     *
     * @param context Any context.
     */
    fun isVoWiFiAvailable(context: Context): Boolean =
        getCallNetworkType(context) == CallNetworkType.VoWiFi ||
                isWifiCallingAvailableDirect(context)

    /**
     * Short label suitable for display in the call screen badge (e.g. "VoLTE", "VoWiFi", "5G").
     * Returns `null` when the call is over a plain GSM/WCDMA radio (no special label needed) or
     * the type is unknown.
     *
     * @param context Any context.
     */
    fun getNetworkBadgeLabel(context: Context): String? =
        when (getCallNetworkType(context)) {
            CallNetworkType.VoLTE    -> "VoLTE"
            CallNetworkType.VoWiFi   -> "VoWiFi"
            CallNetworkType.VoNR     -> "5G"
            CallNetworkType.GSM_WCDMA, CallNetworkType.UNKNOWN -> null
        }

    // -------------------------------------------------------------------------
    // Internal helpers — IMS path (API 29+)
    // -------------------------------------------------------------------------

    private fun getCallNetworkTypeViaIms(
        context: Context,
        telephonyManager: TelephonyManager
    ): CallNetworkType {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Check Wi-Fi calling first (more specific than LTE)
                @Suppress("MissingPermission")
                if (telephonyManager.isWifiCallingAvailableViaReflection()) {
                    return CallNetworkType.VoWiFi
                }

                @Suppress("MissingPermission")
                if (telephonyManager.isVolteAvailable()) {
                    return CallNetworkType.VoLTE
                }

                // Check for 5G voice (VoNR) on API 31+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (isVoNrAvailable(telephonyManager)) {
                        return CallNetworkType.VoNR
                    }
                }
            }
            // Fallback
            getCallNetworkTypeLegacy(telephonyManager)
        } catch (e: Exception) {
            Log.w(TAG, "getCallNetworkTypeViaIms failed: ${e.message}")
            getCallNetworkTypeLegacy(telephonyManager)
        }
    }

    /** Checks VoNR via reflection (API 31+, hidden API, best-effort). */
    private fun isVoNrAvailable(telephonyManager: TelephonyManager): Boolean {
        return try {
            val method = telephonyManager.javaClass.getMethod("isVoNrAvailable")
            method.invoke(telephonyManager) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers — legacy path (API 24-28)
    // -------------------------------------------------------------------------

    private fun getCallNetworkTypeLegacy(telephonyManager: TelephonyManager): CallNetworkType {
        return try {
            val networkType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                @Suppress("MissingPermission")
                telephonyManager.dataNetworkType
            } else {
                @Suppress("DEPRECATION", "MissingPermission")
                telephonyManager.networkType
            }

            // Map the data network type integer to our enum
            when (networkType) {
                TelephonyManager.NETWORK_TYPE_LTE  -> CallNetworkType.VoLTE
                TelephonyManager.NETWORK_TYPE_IWLAN -> CallNetworkType.VoWiFi
                TelephonyManager.NETWORK_TYPE_NR   -> CallNetworkType.VoNR
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_IDEN,
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_EVDO_B,
                TelephonyManager.NETWORK_TYPE_EHRPD,
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_GSM,
                TelephonyManager.NETWORK_TYPE_TD_SCDMA,
                TelephonyManager.NETWORK_TYPE_UNKNOWN -> CallNetworkType.GSM_WCDMA
                else -> CallNetworkType.UNKNOWN
            }
        } catch (e: Exception) {
            Log.w(TAG, "getCallNetworkTypeLegacy failed: ${e.message}")
            CallNetworkType.UNKNOWN
        }
    }

    // -------------------------------------------------------------------------
    // Direct TelephonyManager checks (supplemental)
    // -------------------------------------------------------------------------

    private fun isVoLteAvailableDirect(context: Context): Boolean {
        if (!hasReadPhoneStatePermission(context)) return false
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("MissingPermission")
                tm.isVolteAvailable()
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isWifiCallingAvailableDirect(context: Context): Boolean {
        if (!hasReadPhoneStatePermission(context)) return false
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("MissingPermission")
                tm.isWifiCallingAvailableViaReflection()
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------------
    // Permission helpers
    // -------------------------------------------------------------------------

    private fun hasReadPhoneStatePermission(context: Context): Boolean =
        ActivityCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
}

// Extension function so we can call isVolteAvailable (added API 26, not in all build SDKs)
// without an API-level guard at every call site.
@Suppress("MissingPermission")
private fun TelephonyManager.isVolteAvailable(): Boolean {
    return try {
        val method = this.javaClass.getMethod("isVolteAvailable")
        method.invoke(this) as? Boolean ?: false
    } catch (e: Exception) {
        false
    }
}

@Suppress("MissingPermission")
private fun TelephonyManager.isWifiCallingAvailableViaReflection(): Boolean {
    return try {
        val method = this.javaClass.getMethod("isWifiCallingAvailable")
        method.invoke(this) as? Boolean ?: false
    } catch (e: Exception) {
        false
    }
}
