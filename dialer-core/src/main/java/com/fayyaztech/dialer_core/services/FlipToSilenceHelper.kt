package com.fayyaztech.dialer_core.services

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.util.Log

/**
 * Detects a "flip face-down" gesture using the accelerometer and silences the ringer.
 *
 * ## Registration
 * Call [register] in `Activity.onResume()` (only while the call is ringing).
 * Call [unregister] in `Activity.onPause()` and `Activity.onDestroy()`.
 *
 * ## Detection algorithm
 * The device is considered face-down when the z-axis component reported by the accelerometer
 * is less than [FLIP_THRESHOLD_Z] (−8 m/s², approximately −0.8 g) for at least
 * [CONFIRM_DURATION_MS] milliseconds continuously.  A single noisy sample is not enough to
 * trigger the action, preventing false positives from bumping the phone.
 *
 * ## Ringer management
 * On flip-detection:
 * - The current STREAM_RING volume is saved.
 * - `AudioManager.setStreamVolume(STREAM_RING, 0, 0)` silences the ringer.
 *
 * On [unregister] (call ended / answered / rejected):
 * - The original volume is restored so subsequent incoming calls ring normally.
 *
 * ## Example
 * ```kotlin
 * // In CallScreenActivity
 * private val flipHelper = FlipToSilenceHelper(this)
 *
 * override fun onResume() {
 *     super.onResume()
 *     if (isRinging && IncomingCallPreferences.isFlipToSilenceEnabled(this))
 *         flipHelper.register(onSilenced = { /* update UI */ })
 * }
 * override fun onPause()   { flipHelper.unregister() }
 * override fun onDestroy() { flipHelper.unregister() }
 * ```
 */
class FlipToSilenceHelper(private val context: Context) {

    companion object {
        private const val TAG = "FlipToSilenceHelper"

        /** z-axis threshold (m/s²) – below this value the phone is considered face-down. */
        private const val FLIP_THRESHOLD_Z    = -8f      // ≈ −0.8 g
        /** How long (ms) z must stay below threshold before silencing. */
        private const val CONFIRM_DURATION_MS = 400L
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer  = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val audioManager   = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var onSilenced: (() -> Unit)? = null
    private var hasSilenced = false
    private var savedRingerVolume = -1
    private var flipStartTime = 0L

    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val z = event.values[2]   // positive = face-up, negative = face-down

            if (z < FLIP_THRESHOLD_Z) {
                // z is below threshold — start or continue the confirm timer
                if (flipStartTime == 0L) {
                    flipStartTime = System.currentTimeMillis()
                } else if (!hasSilenced &&
                    System.currentTimeMillis() - flipStartTime >= CONFIRM_DURATION_MS
                ) {
                    silenceRinger()
                }
            } else {
                // z returned above threshold — reset timer (transient bump)
                flipStartTime = 0L
            }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Registers the accelerometer listener.
     *
     * @param onSilenced Optional callback invoked (on the sensor thread) when the ringer is
     *                   silenced.  Use `post {}` if you need to update the UI from here.
     */
    fun register(onSilenced: (() -> Unit)? = null) {
        if (accelerometer == null) {
            Log.w(TAG, "No accelerometer available — flip-to-silence disabled")
            return
        }
        this.onSilenced = onSilenced
        hasSilenced     = false
        flipStartTime   = 0L
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        Log.d(TAG, "Accelerometer listener registered")
    }

    /**
     * Unregisters the listener and restores the ringer volume.
     * Safe to call multiple times.
     */
    fun unregister() {
        sensorManager.unregisterListener(listener)
        restoreRinger()
        onSilenced  = null
        hasSilenced = false
        flipStartTime = 0L
        Log.d(TAG, "Accelerometer listener unregistered")
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun silenceRinger() {
        if (hasSilenced) return
        hasSilenced = true
        try {
            savedRingerVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            Log.d(TAG, "Ringer silenced by flip gesture (saved volume=$savedRingerVolume)")
            onSilenced?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to silence ringer: ${e.message}")
        }
    }

    private fun restoreRinger() {
        if (savedRingerVolume < 0) return
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_RING, savedRingerVolume, 0)
            Log.d(TAG, "Ringer volume restored to $savedRingerVolume")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore ringer volume: ${e.message}")
        } finally {
            savedRingerVolume = -1
        }
    }
}
