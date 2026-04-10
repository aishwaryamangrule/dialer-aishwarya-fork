package com.fayyaztech.dialer_core.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persists speed-dial slot assignments for dialpad digits **1–9**.
 *
 * ## Convention
 * | Slot | Default purpose |
 * |------|----------------|
 * | 1    | Voicemail (user may override with a custom number) |
 * | 2–9  | User-assignable contacts |
 *
 * ## Storage
 * Assignments are kept in a private [SharedPreferences] file (`dialer_speed_dial`).
 * They survive process restarts and updates so the user's assignments persist across sessions.
 *
 * ## Usage
 * ```kotlin
 * // Assign a number to slot 2
 * SpeedDialHelper.setAssignment(context, 2, "+15555550123")
 *
 * // Retrieve it later
 * val number = SpeedDialHelper.getAssignment(context, 2)  // "+15555550123"
 *
 * // Call on long-press
 * val assignment = SpeedDialHelper.getAssignment(context, slot)
 * if (assignment != null) startCall(assignment) else showAssignmentUI()
 * ```
 */
object SpeedDialHelper {

    private const val TAG        = "SpeedDialHelper"
    private const val PREFS_NAME = "dialer_speed_dial"

    /** Conventional voicemail slot. Key 1 is universally reserved for voicemail. */
    const val VOICEMAIL_SLOT = 1

    // -------------------------------------------------------------------------
    // Read / write
    // -------------------------------------------------------------------------

    /**
     * Returns the phone number (or dial string) assigned to [slot], or `null` when empty.
     *
     * @param slot Dialpad digit slot in the range **1–9**.
     * @throws IllegalArgumentException if [slot] is outside 1–9.
     */
    fun getAssignment(context: Context, slot: Int): String? {
        requireSlot(slot)
        return prefs(context).getString(key(slot), null)
    }

    /**
     * Assigns [phoneNumber] to [slot].
     * Pass `null` (or a blank string) to **clear** the assignment.
     *
     * @param slot        Dialpad digit slot in the range **1–9**.
     * @param phoneNumber Number to assign, or `null` to remove.
     * @throws IllegalArgumentException if [slot] is outside 1–9.
     */
    fun setAssignment(context: Context, slot: Int, phoneNumber: String?) {
        requireSlot(slot)
        Log.d(TAG, "setAssignment: slot=$slot → $phoneNumber")
        prefs(context).edit().apply {
            if (phoneNumber.isNullOrBlank()) remove(key(slot))
            else putString(key(slot), phoneNumber.trim())
            apply()
        }
    }

    /**
     * Returns a snapshot of all currently assigned slots as a `slot → phoneNumber` map.
     * Slots without an assignment are omitted from the map.
     */
    fun getAllAssignments(context: Context): Map<Int, String> =
        (1..9).mapNotNull { slot ->
            getAssignment(context, slot)?.let { slot to it }
        }.toMap()

    // -------------------------------------------------------------------------
    // Convenience
    // -------------------------------------------------------------------------

    /**
     * Returns `true` when [slot] is the conventional voicemail slot (i.e. `slot == 1`).
     */
    fun isVoicemailSlot(slot: Int): Boolean = slot == VOICEMAIL_SLOT

    /**
     * Returns the dial string for the voicemail slot.
     * If the user has set a custom number for slot 1 that is returned; otherwise `"voicemail"`.
     */
    fun getVoicemailDialString(context: Context): String =
        getAssignment(context, VOICEMAIL_SLOT) ?: "voicemail"

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun key(slot: Int) = "slot_$slot"

    private fun requireSlot(slot: Int) =
        require(slot in 1..9) { "Speed dial slot must be 1–9, was $slot" }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
