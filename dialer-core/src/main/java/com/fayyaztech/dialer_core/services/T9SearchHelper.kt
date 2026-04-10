package com.fayyaztech.dialer_core.services

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * A single contact entry returned by [T9SearchHelper.search].
 *
 * @param contactId       Row id from [ContactsContract.CommonDataKinds.Phone].
 * @param displayName     Human-readable name (e.g. "Alice Smith").
 * @param phoneNumber     Raw number string as stored by the system (e.g. "+1 (555) 123-4567").
 * @param normalizedNumber E.164 form when available (used for deduplication).
 */
data class T9Contact(
    val contactId: Long,
    val displayName: String,
    val phoneNumber: String,
    val normalizedNumber: String,
    val isStarred: Boolean = false,
    val thumbnailUri: String? = null,
)

/**
 * T9 (multi-tap predictive) smart search over device contacts.
 *
 * ## Digit–letter mapping
 * ```
 * 2 → A B C        5 → J K L        8 → T U V
 * 3 → D E F        6 → M N O        9 → W X Y Z
 * 4 → G H I        7 → P Q R S      0 → (space)
 * ```
 *
 * ## Matching rules
 * A contact matches the query when **either** condition is true:
 *  1. **Name match** — At least one space-separated word in `displayName` has a T9 prefix that
 *     starts with the query digit string (e.g. "52663" matches "Lance" because L→5, A→2, N→6,
 *     C→2, E→3).
 *  2. **Number match** — The phone number's digit-only form **contains** the query string (useful
 *     for partial area-code or subscriber-number searches).
 *
 * Results are sorted by display name and capped at [search]'s `limit` parameter.
 */
object T9SearchHelper {

    private const val TAG = "T9SearchHelper"

    // -------------------------------------------------------------------------
    // Digit ↔ letter mappings
    // -------------------------------------------------------------------------

    /** Maps each dialpad digit to the letters it encodes. */
    private val DIGIT_TO_LETTERS: Map<Char, String> = mapOf(
        '2' to "abc",
        '3' to "def",
        '4' to "ghi",
        '5' to "jkl",
        '6' to "mno",
        '7' to "pqrs",
        '8' to "tuv",
        '9' to "wxyz",
        '0' to " ",
    )

    /** Inverted map: lowercase letter → its dialpad digit. */
    private val LETTER_TO_DIGIT: Map<Char, Char> = buildMap {
        DIGIT_TO_LETTERS.forEach { (digit, letters) ->
            letters.forEach { letter -> put(letter, digit) }
        }
    }

    // -------------------------------------------------------------------------
    // Public helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the T9 dialpad digit for [c], or `null` if the character has no mapping
     * (digits, punctuation, and characters outside the A–Z range return `null`).
     */
    fun charToDigit(c: Char): Char? = LETTER_TO_DIGIT[c.lowercaseChar()]

    /**
     * Converts [name] to its full T9 digit representation.
     * Spaces become `'0'`; non-letter non-space characters are dropped.
     *
     * Examples:
     * - `"Alice"` → `"25423"`
     * - `"Bob Smith"` → `"2620 76484"`  (space → '0')
     */
    fun nameToT9Digits(name: String): String =
        name.mapNotNull { c ->
            when {
                c == ' '    -> '0'
                c.isLetter() -> charToDigit(c)
                else         -> null
            }
        }.joinToString("")

    /**
     * Returns `true` when [name] matches the T9 [digits] query.
     *
     * Matching tries two strategies in order:
     *  1. **Word-boundary match** — any space-separated word whose T9 starts with [digits].
     *  2. **Full-name match** — the entire T9 of [name] starts with [digits].
     *
     * An empty [digits] always returns `true`.
     */
    fun matchesT9(name: String, digits: String): Boolean {
        if (digits.isEmpty()) return true
        // Strategy 1: match from any word boundary
        val words = name.split(Regex("\\s+"))
        if (words.any { word ->
                val t9 = word.mapNotNull { c -> charToDigit(c) }.joinToString("")
                t9.startsWith(digits)
            }) return true
        // Strategy 2: match the full name T9 string
        return nameToT9Digits(name).startsWith(digits)
    }

    // -------------------------------------------------------------------------
    // Contact search
    // -------------------------------------------------------------------------

    /**
     * Searches the device contacts for entries matching the digit [query].
     *
     * Results are deduplicated by normalised phone number and capped at [limit].
     * Requires [android.Manifest.permission.READ_CONTACTS]; returns an empty list if
     * the permission has not been granted.
     *
     * @param context Any `Context`.
     * @param query   Digit-only string (non-digit characters are stripped before matching).
     *                An empty string or a string with no digits returns an empty list.
     * @param limit   Maximum number of contacts to return (default 20).
     * @return Ordered list of matching [T9Contact] entries (display name ASC).
     */
    fun search(context: Context, query: String, limit: Int = 20): List<T9Contact> {
        val digits = query.filter { it.isDigit() }
        if (digits.isEmpty()) return emptyList()
        if (!hasContactsPermission(context)) return emptyList()

        val results     = mutableListOf<T9Contact>()
        val seenNumbers = mutableSetOf<String>() // deduplicate by normalised number

        return try {
            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                    ContactsContract.Contacts.STARRED,
                    ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                ),
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} IS NOT NULL",
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )

            cursor?.use { c ->
                val idxId        = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val idxName      = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val idxNum       = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val idxNorm      = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
                val idxStarred   = c.getColumnIndex(ContactsContract.Contacts.STARRED)
                val idxThumbnail = c.getColumnIndex(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)

                while (c.moveToNext() && results.size < limit) {
                    val name   = c.getString(idxName) ?: continue
                    val number = c.getString(idxNum)  ?: continue
                    val norm   = c.getString(idxNorm) ?: number
                    val id     = c.getLong(idxId)

                    // Skip duplicates (same normalised number from multiple accounts)
                    if (!seenNumbers.add(norm)) continue

                    val digitsOnly = number.replace(Regex("[^0-9]"), "")
                    val numMatches  = digitsOnly.contains(digits)
                    val nameMatches = matchesT9(name, digits)

                    if (numMatches || nameMatches) {
                        val starred   = idxStarred   >= 0 && c.getInt(idxStarred) != 0
                        val thumbnail = if (idxThumbnail >= 0) c.getString(idxThumbnail) else null
                        results.add(T9Contact(id, name, number, norm, starred, thumbnail))
                    }
                }
            }
            results
        } catch (e: Exception) {
            Log.w(TAG, "search() failed: ${e.message}")
            emptyList()
        }
    }

    // -------------------------------------------------------------------------
    // Permission helper
    // -------------------------------------------------------------------------

    private fun hasContactsPermission(context: Context): Boolean =
        ActivityCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
}
