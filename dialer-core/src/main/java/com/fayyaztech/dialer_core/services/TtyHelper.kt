package com.fayyaztech.dialer_core.services

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.TelecomManager
import android.util.Log

/**
 * Helper for TTY (Teletypewriter) and RTT (Real-Time Text) call support.
 *
 * ## TTY
 * TTY allows hearing/speech impaired users to communicate over a phone call using a text keyboard.
 * Android exposes four TTY modes through [TelecomManager]:
 *  - [TTY_MODE_OFF]  — standard voice call (default)
 *  - [TTY_MODE_FULL] — both TTY keyboard and audio
 *  - [TTY_MODE_HCO]  — Hearing Carry Over: user hears audio, types responses
 *  - [TTY_MODE_VCO]  — Voice Carry Over: user speaks, reads TTY output
 *
 * Note: `TelecomManager.setCurrentTtyMode()` is a `@SystemApi` that is callable by the
 * **default dialer** app only. If the app is not the default dialer the call is silently
 * ignored on most OEMs. Reading the current mode via `getCurrentTtyMode()` is always available.
 *
 * ## RTT
 * RTT (Real-Time Text, RFC 4103) is available on API 28+ for IMS/LTE calls.
 * The [Call] object exposes `sendRttRequest()`, `respondToRttRequest()`, and provides a
 * [Call.RttCall] channel for text exchange.
 */
object TtyHelper {

    private const val TAG = "TtyHelper"

    // -------------------------------------------------------------------------
    // TTY mode constants  (mirrors TelecomManager values to avoid a compile-time
    // dependency on the hidden @SystemApi overloads)
    // -------------------------------------------------------------------------

    /** TTY disabled — normal voice call. */
    const val TTY_MODE_OFF: Int = 0

    /** Full TTY — audio + keyboard on both sides. */
    const val TTY_MODE_FULL: Int = 1

    /** Hearing Carry Over — caller hears, types responses. */
    const val TTY_MODE_HCO: Int = 2

    /** Voice Carry Over — caller speaks, reads text responses. */
    const val TTY_MODE_VCO: Int = 3

    // -------------------------------------------------------------------------
    // TTY mode display helpers
    // -------------------------------------------------------------------------

    /** Returns a short human-readable label for a TTY mode constant. */
    fun modeName(mode: Int): String = when (mode) {
        TTY_MODE_OFF  -> "Off"
        TTY_MODE_FULL -> "TTY Full"
        TTY_MODE_HCO  -> "TTY HCO"
        TTY_MODE_VCO  -> "TTY VCO"
        else           -> "Unknown ($mode)"
    }

    /** All selectable TTY modes in display order. */
    val allModes: List<Int> = listOf(TTY_MODE_OFF, TTY_MODE_FULL, TTY_MODE_HCO, TTY_MODE_VCO)

    // -------------------------------------------------------------------------
    // Current TTY mode query
    // -------------------------------------------------------------------------

    /**
     * Returns the device's current TTY mode as one of the [TTY_MODE_*] constants.
     * Available to all apps (not restricted to the default dialer).
     *
     * @param context Any context.
     * @return Current TTY mode, or [TTY_MODE_OFF] on error.
     */
    fun getCurrentTtyMode(context: Context): Int {
        val telecomManager =
            context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return TTY_MODE_OFF
        return try {
            val method = telecomManager.javaClass.getMethod("getCurrentTtyMode")
            (method.invoke(telecomManager) as? Int) ?: TTY_MODE_OFF
        } catch (e: Exception) {
            Log.w(TAG, "getCurrentTtyMode failed: ${e.message}")
            TTY_MODE_OFF
        }
    }

    // -------------------------------------------------------------------------
    // TTY mode change request
    // -------------------------------------------------------------------------

    /**
     * Requests a TTY mode change. Internally calls the hidden
     * `TelecomManager.setCurrentTtyMode(int)` via reflection (the only way to
     * reach it from a non-system app that is nonetheless the default dialer).
     *
     * If reflection fails (e.g. vendor ROM stripped the method) the call is
     * silently ignored and the failure is logged.
     *
     * @param context Android context (default dialer only — see class KDoc).
     * @param mode    One of [TTY_MODE_OFF], [TTY_MODE_FULL], [TTY_MODE_HCO], [TTY_MODE_VCO].
     * @return `true` if the request was sent (does **not** guarantee the OS accepted it).
     */
    fun setTtyMode(context: Context, mode: Int): Boolean {
        if (mode !in allModes) {
            Log.w(TAG, "setTtyMode: unknown mode $mode — ignoring")
            return false
        }
        val telecomManager =
            context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return false
        return try {
            val method = telecomManager.javaClass
                .getMethod("setCurrentTtyMode", Int::class.javaPrimitiveType)
            method.invoke(telecomManager, mode)
            Log.d(TAG, "setCurrentTtyMode($mode) [${modeName(mode)}] sent via reflection")
            true
        } catch (e: Exception) {
            Log.w(TAG, "setCurrentTtyMode via reflection failed: ${e.message}")
            false
        }
    }

    // -------------------------------------------------------------------------
    // RTT helpers (API 28+)
    // -------------------------------------------------------------------------

    /**
     * Returns `true` if the given [Call] is an RTT call (supports real-time text).
     * Always `false` below API 28.
     */
    fun isRttCall(call: Call?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return try {
            call?.isRttActive == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sends an RTT upgrade request to the remote party.
     * The remote must accept via [respondToRttRequest].
     *
     * No-op on API < 28 or when [call] is null.
     *
     * @param call The current active [Call].
     */
    fun requestRtt(call: Call?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || call == null) return
        try {
            call.sendRttRequest()
            Log.d(TAG, "RTT request sent")
        } catch (e: Exception) {
            Log.w(TAG, "sendRttRequest failed: ${e.message}")
        }
    }

    /**
     * Responds to an incoming RTT request from the remote party.
     *
     * @param call    The current call.
     * @param requestId The RTT request ID delivered in [Call.Callback.onRttRequest].
     * @param accept  `true` to accept RTT, `false` to decline.
     */
    fun respondToRttRequest(call: Call?, requestId: Int, accept: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || call == null) return
        try {
            call.respondToRttRequest(requestId, accept)
            Log.d(TAG, "RTT request $requestId responded with accept=$accept")
        } catch (e: Exception) {
            Log.w(TAG, "respondToRttRequest failed: ${e.message}")
        }
    }

    /**
     * Writes a text message over RTT. The remote party will see it in real time.
     *
     * @param call    The current RTT-active [Call].
     * @param message Text to send. Must not be empty.
     */
    fun sendRttMessage(call: Call?, message: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || call == null) return
        if (message.isEmpty()) return
        try {
            val rttCall = call.rttCall
            if (rttCall != null) {
                rttCall.write(message)
                Log.d(TAG, "RTT message sent: $message")
            } else {
                Log.w(TAG, "sendRttMessage: RTT call not active")
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendRttMessage failed: ${e.message}")
        }
    }

    /**
     * Reads any pending RTT text from the remote party.
     * Returns `null` when RTT is inactive or nothing has arrived yet.
     *
     * @param call The current RTT-active [Call].
     */
    fun readRttMessage(call: Call?): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || call == null) return null
        return try {
            call.rttCall?.read()
        } catch (e: Exception) {
            Log.w(TAG, "readRttMessage failed: ${e.message}")
            null
        }
    }

    /**
     * Stops the RTT session on an active RTT call.
     * No-op if RTT is not active or on API < 28.
     */
    fun stopRtt(call: Call?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || call == null) return
        try {
            if (call.isRttActive) {
                call.stopRtt()
                Log.d(TAG, "RTT stopped")
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopRtt failed: ${e.message}")
        }
    }
}
