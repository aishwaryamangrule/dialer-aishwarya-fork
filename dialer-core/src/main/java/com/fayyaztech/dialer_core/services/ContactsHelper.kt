package com.fayyaztech.dialer_core.services

import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.app.ActivityCompat

// =============================================================================
// Data classes
// =============================================================================

/**
 * Lightweight account descriptor.
 *
 * @param name  Account identity string (e.g. "user@gmail.com"; empty for device-local store).
 * @param type  Sync-adapter account type (e.g. "com.google") or null for the device-local store.
 */
data class ContactAccount(
    val name: String,
    val type: String?,
) {
    /** Human-readable label derived from the account type. */
    val label: String
        get() = when (type) {
            "com.google"           -> "Google"
            "com.android.exchange" -> "Exchange"
            "com.osp.app.signin"   -> "Samsung"
            null                   -> "Phone"
            else                   -> type
        }

    companion object {
        /** Represents the device-local (non-synced) store. */
        val LOCAL = ContactAccount(name = "", type = null)
    }
}

/**
 * A single phone number row belonging to a contact.
 *
 * @param dataId          Row id from [ContactsContract.Data._ID].
 * @param number          Raw number string as stored (may contain spaces/dashes).
 * @param normalizedNumber E.164 form when available; falls back to [number].
 * @param typeCode        One of [ContactsContract.CommonDataKinds.Phone.TYPE_*] constants.
 * @param typeLabel       Localised label (e.g. "Mobile", "Home", "Work").
 * @param isPrimary       Whether this is the default number for this contact.
 */
data class ContactPhone(
    val dataId: Long,
    val number: String,
    val normalizedNumber: String,
    val typeCode: Int,
    val typeLabel: String,
    val isPrimary: Boolean = false,
)

/**
 * Full details of a single (potentially aggregated) contact.
 *
 * @param contactId    Row id from [ContactsContract.Contacts._ID].
 * @param rawContactId The first raw-contact row id (used for write operations).
 * @param displayName  Human-readable display name.
 * @param isStarred    Whether the contact is marked as a favourite.
 * @param phones       All phone numbers attached to this contact.
 * @param accountType  Sync-adapter account type of the primary raw contact.
 * @param accountName  Account identity of the primary raw contact.
 * @param photoUri     URI of the full-size photo (may be null).
 * @param thumbnailUri URI of the small thumbnail photo (may be null).
 */
data class ContactDetail(
    val contactId: Long,
    val rawContactId: Long,
    val displayName: String,
    val isStarred: Boolean,
    val phones: List<ContactPhone>,
    val accountType: String?,
    val accountName: String?,
    val photoUri: String?,
    val thumbnailUri: String?,
)

/**
 * A contact entry read directly from a SIM card's ICC/ADN store.
 *
 * @param name   Name stored on the SIM (may be empty for number-only entries).
 * @param number Phone number stored on the SIM.
 */
data class SimContact(
    val name: String,
    val number: String,
)

/**
 * A pair of contacts identified as duplicates by a shared phone number.
 *
 * @param primaryContactId   The contact that should be kept / merged into.
 * @param secondaryContactId The contact to merge from.
 * @param primaryName        Display name of the primary contact.
 * @param secondaryName      Display name of the secondary contact.
 * @param sharedNumber       The normalised number that triggered the match.
 */
data class DuplicateGroup(
    val primaryContactId: Long,
    val secondaryContactId: Long,
    val primaryName: String,
    val secondaryName: String,
    val sharedNumber: String,
)

// =============================================================================
// ContactsHelper object
// =============================================================================

/**
 * Data-access layer for all contacts-related features.
 *
 * ## Covered features
 * 1. Read all contacts — all accounts (Google, phone, SIM-imported, Exchange)
 * 2. Favourites / starred — read list + toggle star flag
 * 3. Create / update / delete contacts via [ContentProviderOperation] batches
 * 4. Account enumeration — lists all accounts that can store contacts
 * 5. SIM contacts — reads ICC/ADN store directly; supports multiple OEM URI variants
 * 6. Import SIM contacts into ContactsContract
 * 7. Duplicate detection + merge via [ContactsContract.AggregationExceptions]
 *
 * ## Permissions
 * | Operation             | Required permission          |
 * |-----------------------|------------------------------|
 * | Read contacts         | READ_CONTACTS                |
 * | Write contacts        | WRITE_CONTACTS               |
 * | Account enumeration   | GET_ACCOUNTS                 |
 * | SIM contacts          | READ_CONTACTS (+ READ_PHONE_STATE on some OEMs) |
 *
 * All methods return empty/null/false when the required permission is absent.
 * **All methods perform I/O — call from a background thread (e.g. Dispatchers.IO).**
 */
object ContactsHelper {

    private const val TAG = "ContactsHelper"

    // SIM ADN URIs tried in order until one succeeds.
    private val SIM_ADN_URIS = listOf(
        "content://icc/adn",            // AOSP / Pixel / most OEMs
        "content://sim/adn",            // Samsung legacy
        "content://contacts/simcontacts", // Some MediaTek ROMs
    )

    // Projection for summary list queries
    private val SUMMARY_PROJECTION = arrayOf(
        ContactsContract.Contacts._ID,
        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        ContactsContract.Contacts.STARRED,
        ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
        ContactsContract.Contacts.HAS_PHONE_NUMBER,
    )

    // =========================================================================
    // READ — contact list
    // =========================================================================

    /**
     * Returns contacts ordered alphabetically, with optional filtering.
     *
     * @param starredOnly  When true, returns only starred (favourite) contacts.
     * @param accountType  When non-null, restricts to contacts from this account type.
     * @param nameQuery    Case-insensitive substring filter on display name.
     * @param limit        Maximum result count (default 500).
     */
    fun getAllContacts(
        context: Context,
        starredOnly: Boolean = false,
        accountType: String? = null,
        nameQuery: String? = null,
        limit: Int = 500,
    ): List<ContactDetail> {
        if (!hasReadPermission(context)) return emptyList()

        val clauses = mutableListOf("${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1")
        val args    = mutableListOf<String>()

        if (starredOnly) clauses += "${ContactsContract.Contacts.STARRED} = 1"
        nameQuery?.let {
            clauses += "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?"
            args    += "%$it%"
        }

        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                SUMMARY_PROJECTION,
                clauses.joinToString(" AND "),
                args.toTypedArray(),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC LIMIT $limit",
            )
            cursor?.use { buildSummaryList(context, it, accountType) } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "getAllContacts failed: ${e.message}")
            emptyList()
        }
    }

    private fun buildSummaryList(
        context: Context,
        cursor: Cursor,
        filterAccountType: String?,
    ): List<ContactDetail> {
        val idxId      = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
        val idxName    = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
        val idxStarred = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.STARRED)
        val idxThumb   = cursor.getColumnIndex(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)

        val results = mutableListOf<ContactDetail>()

        while (cursor.moveToNext()) {
            val contactId = cursor.getLong(idxId)
            val name      = cursor.getString(idxName) ?: ""
            val starred   = cursor.getInt(idxStarred) != 0
            val thumb     = if (idxThumb >= 0) cursor.getString(idxThumb) else null

            val rawInfo = getRawContactAccount(context, contactId)

            // Account-type filter (resolved via sub-query on RawContacts)
            if (filterAccountType != null && rawInfo?.second?.type != filterAccountType) continue

            val phones = getPhonesForContact(context, contactId)
            if (phones.isEmpty()) continue

            results += ContactDetail(
                contactId    = contactId,
                rawContactId = rawInfo?.first ?: -1L,
                displayName  = name,
                isStarred    = starred,
                phones       = phones,
                accountType  = rawInfo?.second?.type,
                accountName  = rawInfo?.second?.name,
                photoUri     = thumb,
                thumbnailUri = thumb,
            )
        }
        return results
    }

    // =========================================================================
    // READ — single contact detail
    // =========================================================================

    /**
     * Returns full detail for a single contact by [contactId], or null if not found.
     * Requires READ_CONTACTS.
     */
    fun getContactDetail(context: Context, contactId: Long): ContactDetail? {
        if (!hasReadPermission(context)) return null
        return try {
            val uri    = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
            val cursor = context.contentResolver.query(uri, SUMMARY_PROJECTION, null, null, null)
            cursor?.use { c ->
                if (!c.moveToFirst()) return null
                val idxId      = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val idxName    = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val idxStarred = c.getColumnIndexOrThrow(ContactsContract.Contacts.STARRED)
                val idxThumb   = c.getColumnIndex(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
                val thumb      = if (idxThumb >= 0) c.getString(idxThumb) else null
                val rawInfo    = getRawContactAccount(context, contactId)
                ContactDetail(
                    contactId    = c.getLong(idxId),
                    rawContactId = rawInfo?.first ?: -1L,
                    displayName  = c.getString(idxName) ?: "",
                    isStarred    = c.getInt(idxStarred) != 0,
                    phones       = getPhonesForContact(context, contactId),
                    accountType  = rawInfo?.second?.type,
                    accountName  = rawInfo?.second?.name,
                    photoUri     = thumb,
                    thumbnailUri = thumb,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "getContactDetail($contactId) failed: ${e.message}")
            null
        }
    }

    // =========================================================================
    // FAVOURITES
    // =========================================================================

    /** Returns all starred contacts. Requires READ_CONTACTS. */
    fun getStarredContacts(context: Context): List<ContactDetail> =
        getAllContacts(context, starredOnly = true)

    /**
     * Sets or clears the starred (favourite) flag for a contact.
     * Requires WRITE_CONTACTS.
     *
     * @return `true` if the update was applied.
     */
    fun setStarred(context: Context, contactId: Long, starred: Boolean): Boolean {
        if (!hasWritePermission(context)) return false
        return try {
            val values = ContentValues().apply {
                put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0)
            }
            val rows = context.contentResolver.update(
                ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
                values, null, null,
            )
            rows > 0
        } catch (e: Exception) {
            Log.w(TAG, "setStarred($contactId, $starred) failed: ${e.message}")
            false
        }
    }

    // =========================================================================
    // WRITE — create / update / delete
    // =========================================================================

    /**
     * Creates a new contact.
     *
     * @param account      Target account (null = device-local store).
     * @param displayName  Display name; may be empty if [phones] is non-empty.
     * @param phones       Phone numbers to attach.
     * @return The new aggregate contact `_ID`, or -1 on failure.
     */
    fun createContact(
        context: Context,
        account: ContactAccount?,
        displayName: String,
        phones: List<ContactPhone>,
    ): Long {
        if (!hasWritePermission(context)) return -1L
        if (displayName.isBlank() && phones.isEmpty()) return -1L
        return try {
            val ops = ArrayList<ContentProviderOperation>()
            val rawIdx = 0 // back-reference index for the RawContact row

            // 1. RawContact row
            ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account?.type)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account?.name?.ifBlank { null })
                .build()

            // 2. Structured name
            if (displayName.isNotBlank()) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIdx)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    )
                    .withValue(
                        ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                        displayName,
                    )
                    .build()
            }

            // 3. Phone rows
            phones.forEach { phone ->
                val customLabel = if (phone.typeCode ==
                    ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM
                ) phone.typeLabel else null

                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIdx)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    )
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone.number)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, phone.typeCode)
                    .withValue(ContactsContract.CommonDataKinds.Phone.LABEL, customLabel)
                    .build()
            }

            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val rawUri = results.firstOrNull()?.uri ?: return -1L
            val rawId  = ContentUris.parseId(rawUri)
            getContactIdFromRawId(context, rawId)
        } catch (e: Exception) {
            Log.w(TAG, "createContact failed: ${e.message}")
            -1L
        }
    }

    /**
     * Updates the display name and phone numbers of an existing contact.
     *
     * Deletes all existing phone Data rows for the first raw contact, then re-inserts from
     * [phones]. The display name is updated in-place.
     *
     * Requires WRITE_CONTACTS.
     *
     * @return `true` on success.
     */
    fun updateContact(
        context: Context,
        contactId: Long,
        displayName: String,
        phones: List<ContactPhone>,
    ): Boolean {
        if (!hasWritePermission(context)) return false
        val rawInfo = getRawContactAccount(context, contactId) ?: return false
        val rawId   = rawInfo.first
        return try {
            val ops = ArrayList<ContentProviderOperation>()

            // Update or insert structured name
            val nameCursor = context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
                null,
            )
            val nameDataId = nameCursor?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L

            if (nameDataId > 0) {
                ops += ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(nameDataId.toString()))
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                    .build()
            } else {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                    .build()
            }

            // Delete all existing phone rows for this raw contact
            ops += ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
                )
                .build()

            // Re-insert phones
            phones.forEach { phone ->
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone.number)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, phone.typeCode)
                    .build()
            }

            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (e: Exception) {
            Log.w(TAG, "updateContact($contactId) failed: ${e.message}")
            false
        }
    }

    /**
     * Permanently deletes a contact (all raw contacts under the aggregate).
     * Requires WRITE_CONTACTS.
     *
     * @return `true` if at least one row was deleted.
     */
    fun deleteContact(context: Context, contactId: Long): Boolean {
        if (!hasWritePermission(context)) return false
        return try {
            val uri  = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
            val rows = context.contentResolver.delete(uri, null, null)
            rows > 0
        } catch (e: Exception) {
            Log.w(TAG, "deleteContact($contactId) failed: ${e.message}")
            false
        }
    }

    // =========================================================================
    // ACCOUNTS
    // =========================================================================

    /**
     * Returns all accounts that can store contacts, plus the device-local store.
     * Requires GET_ACCOUNTS.
     */
    fun getSyncAccounts(context: Context): List<ContactAccount> {
        if (!hasAccountsPermission(context)) return listOf(ContactAccount.LOCAL)
        return try {
            val am = AccountManager.get(context)
            @Suppress("MissingPermission")
            val allAccounts = am.accounts
            val mapped = allAccounts.map { ContactAccount(name = it.name, type = it.type) }
            listOf(ContactAccount.LOCAL) + mapped.distinctBy { it.type to it.name }
        } catch (e: Exception) {
            Log.w(TAG, "getSyncAccounts failed: ${e.message}")
            listOf(ContactAccount.LOCAL)
        }
    }

    // =========================================================================
    // SIM CONTACTS
    // =========================================================================

    /**
     * Reads contacts stored on a SIM card's ICC/ADN store.
     *
     * Tries multiple URIs for compatibility across OEM ROMs (AOSP, Samsung, MediaTek).
     *
     * @param subscriptionId  When >= 0 also tries `content://icc/adn/subId/<N>` first
     *                        (multi-SIM devices, API 22+). Pass -1 for the default single SIM.
     * @return List of [SimContact] entries, or empty if no accessible SIM ADN provider exists.
     */
    fun getSimContacts(context: Context, subscriptionId: Int = -1): List<SimContact> {
        val uris = buildList {
            if (subscriptionId >= 0) add("content://icc/adn/subId/$subscriptionId")
            addAll(SIM_ADN_URIS)
        }
        for (uriString in uris) {
            val result = tryReadSimUri(context, Uri.parse(uriString))
            if (result != null) return result
        }
        Log.w(TAG, "getSimContacts: no accessible SIM ADN URI found on this device")
        return emptyList()
    }

    private fun tryReadSimUri(context: Context, uri: Uri): List<SimContact>? {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
                ?: return null
            val contacts = mutableListOf<SimContact>()
            cursor.use { c ->
                // "name" is standard; some OEMs use "tag"
                val idxName = c.getColumnIndex("name").takeIf { it >= 0 }
                    ?: c.getColumnIndex("tag").takeIf { it >= 0 }
                val idxNum  = c.getColumnIndex("number").takeIf { it >= 0 }
                    ?: return null // no number column — wrong URI
                while (c.moveToNext()) {
                    val number = c.getString(idxNum) ?: continue
                    if (number.isBlank()) continue
                    val name = idxName?.let { c.getString(it) } ?: ""
                    contacts += SimContact(name = name, number = number)
                }
            }
            contacts
        } catch (_: Exception) {
            null // This URI is not supported on this device/ROM
        }
    }

    /**
     * Batch-imports [simContacts] into the system ContactsContract store.
     * Already-imported contacts are not deduplicated here — callers should check first.
     *
     * Requires WRITE_CONTACTS.
     *
     * @return Number of contacts successfully inserted.
     */
    fun importSimContacts(
        context: Context,
        simContacts: List<SimContact>,
        targetAccount: ContactAccount? = null,
    ): Int {
        if (!hasWritePermission(context)) return 0
        var imported = 0
        simContacts.forEach { sim ->
            val id = createContact(
                context      = context,
                account      = targetAccount,
                displayName  = sim.name,
                phones       = listOf(
                    ContactPhone(
                        dataId          = 0,
                        number          = sim.number,
                        normalizedNumber = sim.number,
                        typeCode        = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                        typeLabel       = "Mobile",
                    ),
                ),
            )
            if (id > 0) imported++
        }
        return imported
    }

    // =========================================================================
    // MERGE DUPLICATES
    // =========================================================================

    /**
     * Scans all contacts with phones and returns pairs of contacts that share
     * at least one normalised phone number.
     *
     * Requires READ_CONTACTS.
     *
     * @return List of [DuplicateGroup] pairs; may be empty.
     */
    fun findDuplicates(context: Context): List<DuplicateGroup> {
        if (!hasReadPermission(context)) return emptyList()
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} IS NOT NULL",
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            ) ?: return emptyList()

            // Map: normalised number → list of (contactId, displayName)
            val byNumber = mutableMapOf<String, MutableList<Pair<Long, String>>>()

            cursor.use { c ->
                val idxId   = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val idxName = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val idxNorm = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
                val idxNum  = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (c.moveToNext()) {
                    val contactId = c.getLong(idxId)
                    val name  = c.getString(idxName) ?: ""
                    val norm  = (if (idxNorm >= 0) c.getString(idxNorm) else null)
                        ?: c.getString(idxNum).replace(Regex("[^0-9+]"), "")
                    if (norm.isBlank() || norm.length < 5) continue
                    byNumber.getOrPut(norm) { mutableListOf() } += contactId to name
                }
            }

            val groups    = mutableListOf<DuplicateGroup>()
            val seenPairs = mutableSetOf<Pair<Long, Long>>()

            byNumber.forEach { (number, entries) ->
                val distinct = entries.distinctBy { it.first }
                if (distinct.size < 2) return@forEach
                for (i in 0 until distinct.size - 1) {
                    val (idA, nameA) = distinct[i]
                    val (idB, nameB) = distinct[i + 1]
                    val pairKey = minOf(idA, idB) to maxOf(idA, idB)
                    if (seenPairs.add(pairKey)) {
                        groups += DuplicateGroup(
                            primaryContactId   = idA,
                            secondaryContactId = idB,
                            primaryName        = nameA,
                            secondaryName      = nameB,
                            sharedNumber       = number,
                        )
                    }
                }
            }
            groups
        } catch (e: Exception) {
            Log.w(TAG, "findDuplicates failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Merges [secondaryContactId] into [primaryContactId] using Android's
     * [ContactsContract.AggregationExceptions] mechanism.
     *
     * The system visually aggregates the two contacts into one after this call.
     * Requires WRITE_CONTACTS.
     *
     * @return `true` on success.
     */
    fun mergeContacts(
        context: Context,
        primaryContactId: Long,
        secondaryContactId: Long,
    ): Boolean {
        if (!hasWritePermission(context)) return false
        return try {
            val primaryRaws   = getRawContactIds(context, primaryContactId)
            val secondaryRaws = getRawContactIds(context, secondaryContactId)
            if (primaryRaws.isEmpty() || secondaryRaws.isEmpty()) return false

            val ops = ArrayList<ContentProviderOperation>()
            primaryRaws.forEach { rawId1 ->
                secondaryRaws.forEach { rawId2 ->
                    ops += ContentProviderOperation
                        .newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                        .withValue(
                            ContactsContract.AggregationExceptions.TYPE,
                            ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER,
                        )
                        .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, rawId1)
                        .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, rawId2)
                        .build()
                }
            }
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (e: Exception) {
            Log.w(TAG, "mergeContacts failed: ${e.message}")
            false
        }
    }

    /**
     * Unlinks two raw contacts so the system treats them as separate visible contacts.
     * Requires WRITE_CONTACTS.
     *
     * @return `true` on success.
     */
    fun unlinkContacts(
        context: Context,
        rawContactId1: Long,
        rawContactId2: Long,
    ): Boolean {
        if (!hasWritePermission(context)) return false
        return try {
            val ops = ArrayList<ContentProviderOperation>()
            ops += ContentProviderOperation
                .newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                .withValue(
                    ContactsContract.AggregationExceptions.TYPE,
                    ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE,
                )
                .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, rawContactId1)
                .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, rawContactId2)
                .build()
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (e: Exception) {
            Log.w(TAG, "unlinkContacts failed: ${e.message}")
            false
        }
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Loads all phone numbers for a contact from [ContactsContract.CommonDataKinds.Phone].
     * Returns an empty list on error or if no phones exist.
     */
    fun getPhonesForContact(context: Context, contactId: Long): List<ContactPhone> {
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone._ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                    ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
                ),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null,
            ) ?: return emptyList()

            val phones = mutableListOf<ContactPhone>()
            cursor.use { c ->
                val idxDataId  = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone._ID)
                val idxNum     = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val idxNorm    = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
                val idxType    = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                val idxLabel   = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)
                val idxPrimary = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.IS_PRIMARY)

                while (c.moveToNext()) {
                    val number   = c.getString(idxNum) ?: continue
                    val typeCode = c.getInt(idxType)
                    val rawLabel = if (idxLabel >= 0) c.getString(idxLabel) else null
                    val typeLabel = ContactsContract.CommonDataKinds.Phone
                        .getTypeLabel(context.resources, typeCode, rawLabel)
                        .toString()

                    phones += ContactPhone(
                        dataId          = c.getLong(idxDataId),
                        number          = number,
                        normalizedNumber = if (idxNorm >= 0) c.getString(idxNorm) ?: number else number,
                        typeCode        = typeCode,
                        typeLabel       = typeLabel,
                        isPrimary       = idxPrimary >= 0 && c.getInt(idxPrimary) != 0,
                    )
                }
            }
            phones
        } catch (e: Exception) {
            Log.w(TAG, "getPhonesForContact($contactId) failed: ${e.message}")
            emptyList()
        }
    }

    /** Returns (rawContactId, ContactAccount) for the first raw contact of [contactId]. */
    private fun getRawContactAccount(
        context: Context,
        contactId: Long,
    ): Pair<Long, ContactAccount>? {
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.RawContacts._ID,
                    ContactsContract.RawContacts.ACCOUNT_TYPE,
                    ContactsContract.RawContacts.ACCOUNT_NAME,
                ),
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null,
            ) ?: return null
            cursor.use { c ->
                if (!c.moveToFirst()) return null
                val rawId = c.getLong(c.getColumnIndexOrThrow(ContactsContract.RawContacts._ID))
                val type  = c.getString(c.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE))
                val name  = c.getString(c.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME)) ?: ""
                rawId to ContactAccount(name = name, type = type)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Returns all raw-contact IDs for an aggregate contact. */
    private fun getRawContactIds(context: Context, contactId: Long): List<Long> {
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null,
            ) ?: return emptyList()
            val ids = mutableListOf<Long>()
            cursor.use { c ->
                val idx = c.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
                while (c.moveToNext()) ids += c.getLong(idx)
            }
            ids
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Looks up the aggregate contact _ID for a given raw contact. */
    private fun getContactIdFromRawId(context: Context, rawContactId: Long): Long {
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(rawContactId.toString()),
                null,
            ) ?: return -1L
            cursor.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
        } catch (e: Exception) {
            -1L
        }
    }

    // =========================================================================
    // Permission helpers
    // =========================================================================

    fun hasReadPermission(context: Context): Boolean =
        ActivityCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(context: Context): Boolean =
        ActivityCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasAccountsPermission(context: Context): Boolean =
        ActivityCompat.checkSelfPermission(context, android.Manifest.permission.GET_ACCOUNTS) ==
            PackageManager.PERMISSION_GRANTED
}
