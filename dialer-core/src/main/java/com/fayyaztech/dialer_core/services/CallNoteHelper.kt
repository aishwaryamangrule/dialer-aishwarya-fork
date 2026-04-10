package com.fayyaztech.dialer_core.services

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists free-text notes keyed by phone number.
 *
 * Notes survive across calls and are tied to the number, not a specific call session.
 * The storage key is the digits-only form of the phone number so that formatting
 * differences (spaces, dashes, country prefix) resolve to the same key.
 *
 * ## Usage
 * ```kotlin
 * // In the in-call screen composable:
 * var noteText by remember { mutableStateOf(CallNoteHelper.getNote(context, phoneNumber)) }
 * OutlinedTextField(value = noteText, onValueChange = {
 *     noteText = it
 *     CallNoteHelper.saveNote(context, phoneNumber, it)
 * })
 * ```
 */
object CallNoteHelper {

    private const val PREFS_NAME = "dialer_call_notes"

    /**
     * Returns the saved note for [phoneNumber], or an empty string if none exists.
     */
    fun getNote(context: Context, phoneNumber: String): String =
        prefs(context).getString(key(phoneNumber), "") ?: ""

    /**
     * Saves [text] for [phoneNumber]. Passing an empty string clears the note.
     */
    fun saveNote(context: Context, phoneNumber: String, text: String) {
        prefs(context).edit().putString(key(phoneNumber), text).apply()
    }

    /**
     * Deletes the note for [phoneNumber].
     */
    fun clearNote(context: Context, phoneNumber: String) {
        prefs(context).edit().remove(key(phoneNumber)).apply()
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Normalise [phoneNumber] to digits only so that "+1 800-555 0199" and
     * "18005550199" resolve to the same key. Falls back to a hash code if the
     * number contains no digits (e.g. SIP URIs).
     */
    private fun key(phoneNumber: String): String {
        val digits = phoneNumber.filter { it.isDigit() }
        return digits.ifEmpty { phoneNumber.hashCode().toString() }
    }
}
