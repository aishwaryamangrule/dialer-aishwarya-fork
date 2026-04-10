package com.fayyaztech.dialer_core.services

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists user preferences that govern incoming-call UI behaviour.
 *
 * ## Settings
 * | Key | Values | Default |
 * |-----|--------|---------|
 * | [KEY_DISPLAY_MODE] | [DISPLAY_FULL_SCREEN] / [DISPLAY_BANNER] | [DISPLAY_FULL_SCREEN] |
 * | [KEY_POCKET_MODE] | Boolean | `true` |
 * | [KEY_FLIP_TO_SILENCE] | Boolean | `true` |
 *
 * All settings are stored in the private `dialer_incoming_prefs` preferences file.
 */
object IncomingCallPreferences {

    private const val PREFS_NAME = "dialer_incoming_prefs"

    // ── Keys ──────────────────────────────────────────────────────────────────
    const val KEY_DISPLAY_MODE    = "incoming_display_mode"
    const val KEY_POCKET_MODE     = "pocket_mode_enabled"
    const val KEY_FLIP_TO_SILENCE = "flip_to_silence_enabled"

    // ── Display mode values ────────────────────────────────────────────────────
    /** Show incoming call as a full-screen activity (default). */
    const val DISPLAY_FULL_SCREEN = "full_screen"
    /** Show incoming call as a heads-up notification banner only. */
    const val DISPLAY_BANNER      = "banner"

    // ── Read helpers ───────────────────────────────────────────────────────────

    /**
     * Returns `true` when the full-screen incoming call UI is enabled.
     * Returns `false` when the user has chosen banner-only mode.
     */
    fun isFullScreen(context: Context): Boolean =
        getDisplayMode(context) == DISPLAY_FULL_SCREEN

    fun getDisplayMode(context: Context): String =
        prefs(context).getString(KEY_DISPLAY_MODE, DISPLAY_FULL_SCREEN) ?: DISPLAY_FULL_SCREEN

    fun isPocketModeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POCKET_MODE, true)

    fun isFlipToSilenceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLIP_TO_SILENCE, true)

    // ── Write helpers ──────────────────────────────────────────────────────────

    fun setDisplayMode(context: Context, mode: String) {
        require(mode == DISPLAY_FULL_SCREEN || mode == DISPLAY_BANNER) {
            "Unknown display mode: $mode"
        }
        prefs(context).edit().putString(KEY_DISPLAY_MODE, mode).apply()
    }

    fun setPocketMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_POCKET_MODE, enabled).apply()
    }

    fun setFlipToSilence(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLIP_TO_SILENCE, enabled).apply()
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
