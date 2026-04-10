package com.fayyaztech.dialer_core.services

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores and retrieves SMS rejection message templates.
 *
 * Up to [MAX_TEMPLATES] templates are persisted.  When the user has not customised a slot,
 * the corresponding [DEFAULT_TEMPLATES] value is returned so the feature works immediately
 * after install.
 *
 * Usage:
 * ```kotlin
 * val templates = RejectSmsHelper.getTemplates(context)   // List<String> of 6 items
 * RejectSmsHelper.setTemplate(context, 2, "I'll call you back in 5 minutes")
 * ```
 */
object RejectSmsHelper {

    const val MAX_TEMPLATES = 6
    private const val PREFS_NAME = "dialer_reject_sms"
    private const val KEY_PREFIX  = "template_"

    /** Shipped defaults — shown before the user has made any edits. */
    val DEFAULT_TEMPLATES: List<String> = listOf(
        "Can't talk right now.",
        "I'll call you back.",
        "In a meeting — will call later.",
        "On my way!",
        "Please send a message.",
        "Call you back in 5 minutes.",
    )

    // ── Read ───────────────────────────────────────────────────────────────────

    /**
     * Returns all [MAX_TEMPLATES] rejection message templates.
     * Slots the user hasn't customised fall back to [DEFAULT_TEMPLATES].
     */
    fun getTemplates(context: Context): List<String> {
        val p = prefs(context)
        return (0 until MAX_TEMPLATES).map { idx ->
            p.getString(key(idx), null) ?: DEFAULT_TEMPLATES.getOrElse(idx) { "" }
        }
    }

    /**
     * Returns the template at [index] (0-based), or the default if not customised.
     */
    fun getTemplate(context: Context, index: Int): String {
        require(index in 0 until MAX_TEMPLATES) { "Template index must be 0–${MAX_TEMPLATES - 1}" }
        return prefs(context).getString(key(index), null)
            ?: DEFAULT_TEMPLATES.getOrElse(index) { "" }
    }

    // ── Write ──────────────────────────────────────────────────────────────────

    /**
     * Persists a custom template for [index].
     * Pass a blank string to reset the slot to its default.
     */
    fun setTemplate(context: Context, index: Int, text: String) {
        require(index in 0 until MAX_TEMPLATES) { "Template index must be 0–${MAX_TEMPLATES - 1}" }
        prefs(context).edit().apply {
            if (text.isBlank()) remove(key(index)) else putString(key(index), text.trim())
            apply()
        }
    }

    /** Resets all templates to factory defaults. */
    fun resetToDefaults(context: Context) {
        prefs(context).edit().clear().apply()
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun key(index: Int) = "$KEY_PREFIX$index"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
