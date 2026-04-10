package com.fayyaztech.dialer_core.services

import android.content.Context

/**
 * Stores SIM-selection preferences in [android.content.SharedPreferences].
 *
 * ## Covered preferences
 * - **Ask-each-time** (`askEachTime`): when `true` (default) the SIM picker is always shown
 *   on multi-SIM devices; when `false` the `defaultSimSubId` is used automatically.
 * - **Default SIM subscription ID** (`defaultSimSubId`): the `subscriptionId` of the SIM the
 *   user wants to use by default when ask-each-time is disabled.  `-1` means "not set".
 * - **Per-contact SIM** (`contact_sim_{contactId}`): subscription ID stored per contact ID
 *   so calls to a specific contact always go out on the preferred SIM.
 */
object SimPreferenceHelper {

    private const val PREFS_NAME           = "dialer_sim_preferences"
    private const val KEY_ASK_EACH_TIME    = "ask_each_time"
    private const val KEY_DEFAULT_SIM_SUB  = "default_sim_sub_id"
    private const val KEY_CONTACT_SIM_PREFIX = "contact_sim_"

    // ── Ask-each-time ─────────────────────────────────────────────────────────

    /** Returns `true` when the SIM picker should always appear on multi-SIM devices. */
    fun isAskEachTime(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASK_EACH_TIME, true)

    fun setAskEachTime(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ASK_EACH_TIME, value).apply()

    // ── Default SIM ───────────────────────────────────────────────────────────

    /**
     * Returns the user's preferred default SIM subscription ID, or `-1` if none is set.
     * Only meaningful when [isAskEachTime] is `false`.
     */
    fun getDefaultSimSubId(context: Context): Int =
        prefs(context).getInt(KEY_DEFAULT_SIM_SUB, -1)

    /**
     * Saves [subscriptionId] as the preferred default SIM and disables ask-each-time,
     * so future calls use this SIM automatically without showing the picker.
     */
    fun setDefaultSimSubId(context: Context, subscriptionId: Int) {
        prefs(context).edit()
            .putInt(KEY_DEFAULT_SIM_SUB, subscriptionId)
            .putBoolean(KEY_ASK_EACH_TIME, false)
            .apply()
    }

    /** Clears the default SIM and re-enables ask-each-time. */
    fun clearDefaultSim(context: Context) {
        prefs(context).edit()
            .remove(KEY_DEFAULT_SIM_SUB)
            .putBoolean(KEY_ASK_EACH_TIME, true)
            .apply()
    }

    // ── Per-contact SIM ───────────────────────────────────────────────────────

    /**
     * Returns the preferred SIM subscription ID for [contactId], or `-1` if none is set.
     */
    fun getContactSimSubId(context: Context, contactId: Long): Int =
        prefs(context).getInt("$KEY_CONTACT_SIM_PREFIX$contactId", -1)

    /**
     * Saves [subscriptionId] as the preferred SIM for [contactId].
     * Pass `subscriptionId = -1` to clear the preference.
     */
    fun setContactSimSubId(context: Context, contactId: Long, subscriptionId: Int) {
        if (subscriptionId < 0) {
            prefs(context).edit().remove("$KEY_CONTACT_SIM_PREFIX$contactId").apply()
        } else {
            prefs(context).edit().putInt("$KEY_CONTACT_SIM_PREFIX$contactId", subscriptionId).apply()
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
