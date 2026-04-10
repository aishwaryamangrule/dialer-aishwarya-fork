package com.fayyaztech.dialer_core.services

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CallLog
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * Call-type constants that map 1-to-1 to [android.provider.CallLog.Calls] type values.
 *
 * Android defines:
 *  - INCOMING_TYPE  = 1
 *  - OUTGOING_TYPE  = 2
 *  - MISSED_TYPE    = 3
 *  - VOICEMAIL_TYPE = 4
 *  - REJECTED_TYPE  = 5  (API 24+)
 *  - BLOCKED_TYPE   = 6  (API 24+)
 */
enum class CallType(val code: Int) {
    INCOMING(CallLog.Calls.INCOMING_TYPE),
    OUTGOING(CallLog.Calls.OUTGOING_TYPE),
    MISSED(CallLog.Calls.MISSED_TYPE),
    VOICEMAIL(CallLog.Calls.VOICEMAIL_TYPE),
    REJECTED(5),   // CallLog.Calls.REJECTED_TYPE — defined API 24+ as literal
    BLOCKED(6);    // CallLog.Calls.BLOCKED_TYPE  — defined API 24+ as literal

    companion object {
        fun fromCode(code: Int): CallType = entries.firstOrNull { it.code == code } ?: INCOMING
    }
}

/**
 * A single call-log entry.
 *
 * @param id             System row id from [CallLog.Calls._ID].
 * @param phoneNumber    Caller/callee number exactly as stored by the system.
 * @param contactName    Display name from the contacts database, or null.
 * @param callType       One of [CallType] (incoming, outgoing, missed, rejected, blocked, voicemail).
 * @param dateMs         UTC timestamp of the call start in milliseconds.
 * @param durationSec    Duration of the call in seconds. 0 for missed/rejected/blocked calls.
 * @param simLabel       Human-readable subscription label (e.g. "Carrier A") or null.
 * @param isRead         Whether the missed-call entry has been marked read (seen by the user).
 */
data class CallLogEntry(
    val id: Long,
    val phoneNumber: String,
    val contactName: String?,
    val callType: CallType,
    val dateMs: Long,
    val durationSec: Long,
    val simLabel: String?,
    val isRead: Boolean,
)

/**
 * Filter criteria passed to [CallLogHelper.query].
 *
 * All fields are optional — omitting a field means "no restriction on this dimension".
 *
 * @param types     Restrict to these call types. Empty = all types.
 * @param simLabel  Restrict to entries whose [CallLogEntry.simLabel] matches.
 *                  Matching is case-insensitive substring. Null = all SIMs.
 * @param fromMs    Only entries at or after this UTC timestamp. Null = no lower bound.
 * @param toMs      Only entries at or before this UTC timestamp. Null = no upper bound.
 * @param contact   Restrict to entries whose [CallLogEntry.phoneNumber] or
 *                  [CallLogEntry.contactName] contains this string (case-insensitive).
 *                  Null = all contacts.
 * @param limit     Maximum rows to return. Null = system default (500).
 */
data class CallLogFilter(
    val types: List<CallType> = emptyList(),
    val simLabel: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val contact: String? = null,
    val limit: Int? = null,
)

/**
 * Data-access object for the system call log.
 *
 * ## Permissions required
 * - [android.Manifest.permission.READ_CALL_LOG] — to read entries.
 * - [android.Manifest.permission.WRITE_CALL_LOG] — to mark entries as read or delete them.
 *
 * Neither permission is requested here — callers must ensure they are granted before invoking
 * any public method. Methods return empty lists / false when the permission is absent.
 *
 * ## Threading
 * All public methods perform I/O and **must be called off the main thread** (e.g. via
 * `Dispatchers.IO`).
 */
object CallLogHelper {

    private const val TAG = "CallLogHelper"

    // The "account_label" column stores the SIM card / phone account display name.
    // The constant CallLog.Calls.PHONE_ACCOUNT_LABEL is hidden in many SDK stubs, so we
    // reference it by its raw column name.
    private const val COLUMN_ACCOUNT_LABEL = "account_label"

    // Columns we project in every query — keeps cursors small.
    private val PROJECTION = arrayOf(
        CallLog.Calls._ID,
        CallLog.Calls.NUMBER,
        CallLog.Calls.CACHED_NAME,
        CallLog.Calls.TYPE,
        CallLog.Calls.DATE,
        CallLog.Calls.DURATION,
        COLUMN_ACCOUNT_LABEL,
        CallLog.Calls.IS_READ,
    )

    // -------------------------------------------------------------------------
    // Query / read
    // -------------------------------------------------------------------------

    /**
     * Returns call-log entries ordered newest-first, applying optional [filter] criteria.
     *
     * Requires [android.Manifest.permission.READ_CALL_LOG]. Returns an empty list if
     * the permission is not granted or if any I/O error occurs.
     *
     * @param context Any context.
     * @param filter  Optional filter; use [CallLogFilter] defaults for "all calls".
     */
    fun query(context: Context, filter: CallLogFilter = CallLogFilter()): List<CallLogEntry> {
        if (!hasReadPermission(context)) {
            Log.w(TAG, "READ_CALL_LOG not granted — returning empty list")
            return emptyList()
        }

        val (selection, selectionArgs) = buildSelection(filter)
        val limit = filter.limit?.toString() ?: "500"

        return try {
            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                PROJECTION,
                selection.ifEmpty { null },
                selectionArgs.toTypedArray().ifEmpty { null },
                "${CallLog.Calls.DATE} DESC LIMIT $limit",
            )
            cursor?.use { parseCursor(it, filter) } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "query() failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Returns the number of unread missed calls (badge count).
     *
     * Requires [android.Manifest.permission.READ_CALL_LOG]. Returns 0 on error.
     */
    fun unreadMissedCount(context: Context): Int {
        if (!hasReadPermission(context)) return 0
        return try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID),
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.IS_READ} = 0",
                arrayOf(CallType.MISSED.code.toString()),
                null,
            )
            cursor?.use { it.count } ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "unreadMissedCount() failed: ${e.message}")
            0
        }
    }

    // -------------------------------------------------------------------------
    // Update — mark as read
    // -------------------------------------------------------------------------

    /**
     * Marks all unread missed-call entries as read.
     * Requires [android.Manifest.permission.WRITE_CALL_LOG].
     *
     * @return Number of rows updated, or -1 on error / permission denied.
     */
    fun markAllMissedAsRead(context: Context): Int {
        if (!hasWritePermission(context)) return -1
        return try {
            val values = ContentValues().apply { put(CallLog.Calls.IS_READ, 1) }
            context.contentResolver.update(
                CallLog.Calls.CONTENT_URI,
                values,
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.IS_READ} = 0",
                arrayOf(CallType.MISSED.code.toString()),
            )
        } catch (e: Exception) {
            Log.w(TAG, "markAllMissedAsRead() failed: ${e.message}")
            -1
        }
    }

    /**
     * Marks a single entry as read by [id].
     * Requires [android.Manifest.permission.WRITE_CALL_LOG].
     */
    fun markAsRead(context: Context, id: Long): Boolean {
        if (!hasWritePermission(context)) return false
        return try {
            val values = ContentValues().apply { put(CallLog.Calls.IS_READ, 1) }
            val rows = context.contentResolver.update(
                CallLog.Calls.CONTENT_URI,
                values,
                "${CallLog.Calls._ID} = ?",
                arrayOf(id.toString()),
            )
            rows > 0
        } catch (e: Exception) {
            Log.w(TAG, "markAsRead($id) failed: ${e.message}")
            false
        }
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    /**
     * Deletes a single call-log entry by [id].
     * Requires [android.Manifest.permission.WRITE_CALL_LOG].
     *
     * @return `true` if the row was deleted.
     */
    fun deleteEntry(context: Context, id: Long): Boolean {
        if (!hasWritePermission(context)) return false
        return try {
            val rows = context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls._ID} = ?",
                arrayOf(id.toString()),
            )
            rows > 0
        } catch (e: Exception) {
            Log.w(TAG, "deleteEntry($id) failed: ${e.message}")
            false
        }
    }

    /**
     * Bulk-deletes all entries whose [CallLogEntry.id] is in [ids].
     * Requires [android.Manifest.permission.WRITE_CALL_LOG].
     *
     * @return Number of rows deleted, or -1 on error / permission denied.
     */
    fun deleteEntries(context: Context, ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        if (!hasWritePermission(context)) return -1
        return try {
            val placeholders = ids.joinToString(",") { "?" }
            context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls._ID} IN ($placeholders)",
                ids.map { it.toString() }.toTypedArray(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "deleteEntries() failed: ${e.message}")
            -1
        }
    }

    /**
     * Deletes all call-log entries matching [filter].
     * Requires [android.Manifest.permission.WRITE_CALL_LOG].
     *
     * Call with an empty [CallLogFilter] to clear the entire log.
     *
     * @return Number of rows deleted, or -1 on error / permission denied.
     */
    fun deleteAll(context: Context, filter: CallLogFilter = CallLogFilter()): Int {
        if (!hasWritePermission(context)) return -1
        val (selection, selectionArgs) = buildSelection(filter)
        return try {
            context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                selection.ifEmpty { null },
                selectionArgs.toTypedArray().ifEmpty { null },
            )
        } catch (e: Exception) {
            Log.w(TAG, "deleteAll() failed: ${e.message}")
            -1
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a WHERE clause + args list from [filter].
     * Returns a pair of (selectionString, argsList).
     */
    private fun buildSelection(filter: CallLogFilter): Pair<String, List<String>> {
        val clauses = mutableListOf<String>()
        val args    = mutableListOf<String>()

        // Call type filter
        if (filter.types.isNotEmpty()) {
            val placeholders = filter.types.joinToString(",") { "?" }
            clauses += "${CallLog.Calls.TYPE} IN ($placeholders)"
            args    += filter.types.map { it.code.toString() }
        }

        // Date range
        filter.fromMs?.let { clauses += "${CallLog.Calls.DATE} >= ?"; args += it.toString() }
        filter.toMs?.let   { clauses += "${CallLog.Calls.DATE} <= ?"; args += it.toString() }

        return clauses.joinToString(" AND ") to args
    }

    /** Reads all rows from [cursor] into a list of [CallLogEntry], applying in-memory filters. */
    private fun parseCursor(cursor: Cursor, filter: CallLogFilter): List<CallLogEntry> {
        val idxId       = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
        val idxNumber   = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
        val idxName     = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
        val idxType     = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
        val idxDate     = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
        val idxDuration = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
        val idxSim      = cursor.getColumnIndex(COLUMN_ACCOUNT_LABEL)
        val idxIsRead   = cursor.getColumnIndexOrThrow(CallLog.Calls.IS_READ)

        val results = mutableListOf<CallLogEntry>()

        while (cursor.moveToNext()) {
            val number = cursor.getString(idxNumber) ?: ""
            val name   = cursor.getString(idxName)
            val simLbl = if (idxSim >= 0) cursor.getString(idxSim) else null

            // In-memory contact-name/number filter
            val contactQuery = filter.contact
            if (contactQuery != null) {
                val q = contactQuery.lowercase()
                val matchesNum  = number.lowercase().contains(q)
                val matchesName = name?.lowercase()?.contains(q) == true
                if (!matchesNum && !matchesName) continue
            }

            // In-memory SIM label filter
            val simQuery = filter.simLabel
            if (simQuery != null && simLbl?.contains(simQuery, ignoreCase = true) != true) {
                continue
            }

            results += CallLogEntry(
                id          = cursor.getLong(idxId),
                phoneNumber = number,
                contactName = name,
                callType    = CallType.fromCode(cursor.getInt(idxType)),
                dateMs      = cursor.getLong(idxDate),
                durationSec = cursor.getLong(idxDuration),
                simLabel    = simLbl,
                isRead      = cursor.getInt(idxIsRead) != 0,
            )
        }
        return results
    }

    // -------------------------------------------------------------------------
    // Permission helpers
    // -------------------------------------------------------------------------

    fun hasReadPermission(context: Context): Boolean =
        ActivityCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CALL_LOG,
        ) == PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(context: Context): Boolean =
        ActivityCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_CALL_LOG,
        ) == PackageManager.PERMISSION_GRANTED
}
