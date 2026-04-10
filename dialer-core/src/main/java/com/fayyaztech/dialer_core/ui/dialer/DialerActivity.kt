@file:OptIn(ExperimentalFoundationApi::class)

package com.fayyaztech.dialer_core.ui.dialer

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.fayyaztech.dialer_core.services.SimSelectionHelper
import com.fayyaztech.dialer_core.services.SpeedDialHelper
import com.fayyaztech.dialer_core.services.T9Contact
import com.fayyaztech.dialer_core.services.T9SearchHelper
import com.fayyaztech.dialer_core.ui.contacts.ContactDetailActivity
import com.fayyaztech.dialer_core.ui.contacts.ContactsActivity
import com.fayyaztech.dialer_core.ui.theme.DefaultDialerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =============================================================================
// Activity
// =============================================================================

/**
 * Pre-call dialer screen implementing:
 *  - **T9 smart dial** — as digits are typed, contacts are searched by T9 name mapping and
 *    phone-number substring, with a debounced coroutine query on [Dispatchers.IO].
 *  - **Copy / paste** — a "Paste" button reads dialable characters from the clipboard; any
 *    character that is not a digit, `+`, `*`, or `#` is silently dropped.
 *  - **Speed dial (long-press 1–9)** — long-pressing a digit key shows a dialog to call,
 *    assign, reassign, or clear the slot. Slot 1 is the conventional voicemail slot.
 *    Long-pressing `0` appends `+` for international dialing.
 *
 * The activity handles `ACTION_DIAL` and `ACTION_VIEW` intents with `tel:` URIs, pre-filling
 * the dial input with the number from the URI.
 */
class DialerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DialerActivity"
        private const val T9_DEBOUNCE_MS     = 300L
        private const val REQ_CALL_PERMISSION = 101
    }

    // ── UI state (MutableState so Compose recomposes on change) ──────────────
    private var dialedNumber  by mutableStateOf("")
    private var searchResults by mutableStateOf<List<T9Contact>>(emptyList())
    private var isSearching   by mutableStateOf(false)
    private var speedDialMap  by mutableStateOf<Map<Int, String>>(emptyMap())
    private var simList       by mutableStateOf<List<SimSelectionHelper.SimInfo>>(emptyList())

    // Speed-dial dialog
    private var sdDialogSlot    by mutableStateOf<Int?>(null)
    private var sdDialogCurrent by mutableStateOf<String?>(null)

    // SIM picker dialog
    private var showSimPicker by mutableStateOf(false)
    private var pendingNumber by mutableStateOf("")

    private var searchJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pre-fill from ACTION_DIAL / ACTION_VIEW / explicit extra
        dialedNumber = extractNumber(intent) ?: ""
        speedDialMap = SpeedDialHelper.getAllAssignments(this)
        simList      = SimSelectionHelper.getAvailableSims(this)

        if (dialedNumber.isNotEmpty()) scheduleT9Search(dialedNumber)

        setContent {
            DefaultDialerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = BgDark,
                ) {
                    DialerScreen(
                        dialedNumber     = dialedNumber,
                        searchResults    = searchResults,
                        isSearching      = isSearching,
                        speedDialMap     = speedDialMap,
                        onDigit          = ::onDigit,
                        onBackspace      = ::onBackspace,
                        onClearAll       = ::clearAll,
                        onCall           = ::callWithCurrentNumber,
                        onPaste          = ::pasteFromClipboard,
                        onContactPick    = { initiateCall(it.phoneNumber) },
                        onContactView    = { contact -> openContactDetail(contact.contactId) },
                        onOpenContacts   = ::openContacts,
                        onSpeedDialLong  = ::onSpeedDialLongPress,
                        // Speed-dial dialog
                        sdDialogSlot        = sdDialogSlot,
                        sdDialogCurrent     = sdDialogCurrent,
                        onSdDialogDismiss   = { sdDialogSlot = null },
                        onSdCall            = { num -> sdDialogSlot = null; initiateCall(num) },
                        onSdAssignCurrent   = { slot ->
                            val n = dialedNumber
                            if (n.isNotEmpty()) assignSpeedDial(slot, n)
                        },
                        onSdClear           = ::clearSpeedDial,
                        // SIM picker
                        showSimPicker       = showSimPicker,
                        simList             = simList,
                        onSimSelected       = { handle ->
                            showSimPicker = false
                            SimSelectionHelper.placeCall(this, pendingNumber, handle)
                        },
                        onSimPickerDismiss  = { showSimPicker = false },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val n = extractNumber(intent) ?: return
        if (n != dialedNumber) {
            dialedNumber = n
            scheduleT9Search(n)
        }
    }

    // ── Digit input ───────────────────────────────────────────────────────────

    private fun onDigit(digit: Char) {
        dialedNumber += digit
        scheduleT9Search(dialedNumber)
    }

    private fun onBackspace() {
        if (dialedNumber.isNotEmpty()) {
            dialedNumber = dialedNumber.dropLast(1)
            scheduleT9Search(dialedNumber)
        }
    }

    private fun clearAll() {
        dialedNumber  = ""
        searchResults = emptyList()
        searchJob?.cancel()
    }

    /** Reads the clipboard and extracts dialable characters (digits + +, *, #). */
    private fun pasteFromClipboard() {
        try {
            val cm  = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
            // Keep only characters valid in a dialable number
            val dialable = raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
            if (dialable.isEmpty()) return
            dialedNumber = dialable
            scheduleT9Search(dialable)
            Log.d(TAG, "Pasted: $dialable")
        } catch (e: Exception) {
            Log.w(TAG, "Paste failed: ${e.message}")
        }
    }

    // ── T9 search ─────────────────────────────────────────────────────────────

    private fun scheduleT9Search(query: String) {
        searchJob?.cancel()
        val digits = query.filter { it.isDigit() }
        if (digits.isEmpty()) { searchResults = emptyList(); return }

        searchJob = lifecycleScope.launch {
            delay(T9_DEBOUNCE_MS)
            isSearching   = true
            val hits      = withContext(Dispatchers.IO) { T9SearchHelper.search(this@DialerActivity, digits) }
            searchResults = hits
            isSearching   = false
        }
    }

    // ── Call initiation ───────────────────────────────────────────────────────

    private fun callWithCurrentNumber() {
        val n = dialedNumber.trim()
        if (n.isNotEmpty()) initiateCall(n)
    }

    private fun initiateCall(number: String) {
        if (number.isBlank()) return
        if (!hasCallPermission()) {
            pendingNumber = number
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL_PERMISSION,
            )
            return
        }
        // Refresh SIM list right before placing the call
        simList = SimSelectionHelper.getAvailableSims(this)
        when {
            simList.size > 1 -> {
                // Multiple SIMs — let the user pick
                pendingNumber = number
                showSimPicker = true
            }
            simList.size == 1 -> SimSelectionHelper.placeCall(this, number, simList[0].phoneAccountHandle)
            else              -> placeCallFallback(number)
        }
    }

    private fun placeCallFallback(number: String) {
        try {
            val uri = Uri.parse("tel:${Uri.encode(number)}")
            startActivity(
                Intent(Intent.ACTION_CALL, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Fallback call failed: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALL_PERMISSION &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            val n = pendingNumber
            if (n.isNotEmpty()) { initiateCall(n); pendingNumber = "" }
        }
    }

    // ── Speed dial ────────────────────────────────────────────────────────────

    private fun onSpeedDialLongPress(slot: Int) {
        sdDialogSlot    = slot
        sdDialogCurrent = SpeedDialHelper.getAssignment(this, slot)
    }

    private fun assignSpeedDial(slot: Int, number: String) {
        SpeedDialHelper.setAssignment(this, slot, number)
        speedDialMap = SpeedDialHelper.getAllAssignments(this)
        sdDialogSlot = null
        Log.d(TAG, "Speed dial [$slot] = $number")
    }

    private fun clearSpeedDial(slot: Int) {
        SpeedDialHelper.setAssignment(this, slot, null)
        speedDialMap = SpeedDialHelper.getAllAssignments(this)
        sdDialogSlot = null
    }

    private fun openContactDetail(contactId: Long?) {
        val intent = Intent(this, ContactDetailActivity::class.java).apply {
            if (contactId != null) putExtra(ContactDetailActivity.EXTRA_CONTACT_ID, contactId)
        }
        startActivity(intent)
    }

    private fun openContacts() {
        startActivity(Intent(this, ContactsActivity::class.java))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hasCallPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED

    /** Extracts a dialable number from an `ACTION_DIAL`/`ACTION_VIEW` intent or an explicit extra. */
    private fun extractNumber(intent: Intent?): String? {
        intent ?: return null
        intent.data?.let { uri ->
            when (uri.scheme) {
                "tel"      -> return Uri.decode(uri.schemeSpecificPart)
                "voicemail" -> return uri.schemeSpecificPart
            }
        }
        return intent.getStringExtra("PHONE_NUMBER")
    }
}

// =============================================================================
// Color palette (shared by all composables in this file)
// =============================================================================

private val BgDark      = Color(0xFF0A0F1C)
private val BgSurface   = Color(0xFF1A2138)
private val BgButton    = Color(0xFF2A3152)
private val AccentBlue  = Color(0xFF4A6FFF)
private val AccentGreen = Color(0xFF2ECC71)
private val TextSub     = Color(0xFF8F9BB3)

// =============================================================================
// Composable — DialerScreen
// =============================================================================

/**
 * Pure composable that renders the dialer UI.
 * All state mutations are delegated back to the Activity through lambdas.
 */
@Composable
internal fun DialerScreen(
    dialedNumber: String,
    searchResults: List<T9Contact>,
    isSearching: Boolean,
    speedDialMap: Map<Int, String>,
    // Digit input
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClearAll: () -> Unit,
    onCall: () -> Unit,
    onPaste: () -> Unit,
    onContactPick: (T9Contact) -> Unit,
    onContactView: (T9Contact) -> Unit,
    onOpenContacts: () -> Unit,
    onSpeedDialLong: (Int) -> Unit,
    // Speed-dial dialog
    sdDialogSlot: Int?,
    sdDialogCurrent: String?,
    onSdDialogDismiss: () -> Unit,
    onSdCall: (String) -> Unit,
    onSdAssignCurrent: (Int) -> Unit,
    onSdClear: (Int) -> Unit,
    // SIM picker
    showSimPicker: Boolean,
    simList: List<SimSelectionHelper.SimInfo>,
    onSimSelected: (PhoneAccountHandle?) -> Unit,
    onSimPickerDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier             = Modifier
                .fillMaxSize()
                .background(BgDark)
                .padding(horizontal = 16.dp)
                .padding(top = 40.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Contacts shortcut button in top-right corner
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                androidx.compose.material3.IconButton(onClick = onOpenContacts) {
                    Icon(
                        imageVector        = Icons.Default.Person,
                        contentDescription = "Contacts",
                        tint               = TextSub,
                        modifier           = Modifier.size(24.dp),
                    )
                }
            }

            // 1 ── Number display (input + backspace + paste)
            NumberDisplay(
                number       = dialedNumber,
                onBackspace  = onBackspace,
                onClearAll   = onClearAll,
                onPaste      = onPaste,
            )

            Spacer(Modifier.height(8.dp))

            // 2 ── T9 search results (visible while digits are typed and results exist)
            if (searchResults.isNotEmpty() || isSearching) {
                T9ResultsList(
                    results        = searchResults,
                    isSearching    = isSearching,
                    onSelect       = onContactPick,
                    onContactView  = onContactView,
                    modifier       = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.weight(1f))

            // 3 ── Keypad
            DialerKeypad(
                speedDialMap    = speedDialMap,
                onDigit         = onDigit,
                onSpeedDialLong = onSpeedDialLong,
            )

            Spacer(Modifier.height(28.dp))

            // 4 ── Call button
            FloatingActionButton(
                onClick        = onCall,
                modifier       = Modifier.size(72.dp),
                containerColor = AccentGreen,
                shape          = CircleShape,
            ) {
                Icon(
                    imageVector        = Icons.Default.Call,
                    contentDescription = "Call",
                    tint               = Color.White,
                    modifier           = Modifier.size(32.dp),
                )
            }
        }

        // ── Overlay dialogs ────────────────────────────────────────────────────

        if (sdDialogSlot != null) {
            SpeedDialDialog(
                slot                  = sdDialogSlot,
                currentAssignment     = sdDialogCurrent,
                hasCurrentDialedNumber = dialedNumber.isNotEmpty(),
                onDismiss             = onSdDialogDismiss,
                onCall                = onSdCall,
                onAssignCurrent       = { onSdAssignCurrent(sdDialogSlot) },
                onClear               = { onSdClear(sdDialogSlot) },
            )
        }

        if (showSimPicker && simList.size > 1) {
            SimPickerDialog(
                simList   = simList,
                onSelect  = onSimSelected,
                onDismiss = onSimPickerDismiss,
            )
        }
    }
}

// =============================================================================
// NumberDisplay — large text + backspace (long-press = clear) + paste button
// =============================================================================

@Composable
private fun NumberDisplay(
    number: String,
    onBackspace: () -> Unit,
    onClearAll: () -> Unit,
    onPaste: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier            = Modifier.fillMaxWidth(),
            verticalAlignment   = Alignment.CenterVertically,
        ) {
            Text(
                text       = number.ifEmpty { "Enter number" },
                style      = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Light,
                color      = if (number.isEmpty()) TextSub else Color.White,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                textAlign  = TextAlign.Start,
                modifier   = Modifier.weight(1f),
            )
            // Backspace — single tap removes last char, long-press clears all
            if (number.isNotEmpty()) {
                Box(
                    modifier           = Modifier
                        .size(48.dp)
                        .combinedClickable(
                            onClick     = onBackspace,
                            onLongClick = onClearAll,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = Icons.Default.Backspace,
                        contentDescription = "Backspace (long-press to clear)",
                        tint               = TextSub,
                        modifier           = Modifier.size(24.dp),
                    )
                }
            }
        }

        // Paste button — always visible for discoverability
        TextButton(
            onClick  = onPaste,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(
                text  = "Paste",
                color = AccentBlue,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

// =============================================================================
// T9ResultsList — lazy list of matching contacts
// =============================================================================

@Composable
private fun T9ResultsList(
    results: List<T9Contact>,
    isSearching: Boolean,
    onSelect: (T9Contact) -> Unit,
    onContactView: (T9Contact) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (isSearching && results.isEmpty()) {
            CircularProgressIndicator(
                modifier    = Modifier
                    .size(24.dp)
                    .align(Alignment.Center),
                color       = AccentBlue,
                strokeWidth = 2.dp,
            )
        } else {
            LazyColumn(
                modifier            = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(
                    items = results,
                    key   = { "${it.contactId}_${it.normalizedNumber}" },
                ) { contact ->
                    T9ResultRow(
                        contact     = contact,
                        onClick     = { onContactView(contact) },
                        onCallClick = { onSelect(contact) },
                    )
                }
            }
        }
    }
}

@Composable
private fun T9ResultRow(contact: T9Contact, onClick: () -> Unit, onCallClick: () -> Unit) {
    Row(
        modifier            = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface)
            .clickable(onClick = onClick)   // row body -> view contact detail
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment   = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Avatar circle with contact initial
        Box(
            modifier         = Modifier
                .size(40.dp)
                .background(AccentBlue.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text       = contact.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color      = AccentBlue,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = contact.displayName,
                color      = Color.White,
                fontWeight = FontWeight.Medium,
                style      = MaterialTheme.typography.bodyMedium,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Text(
                text     = contact.phoneNumber,
                color    = TextSub,
                style    = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        // Call icon - tapping this calls directly without opening the contact card
        Box(
            modifier         = Modifier
                .size(36.dp)
                .clickable(onClick = onCallClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Default.Call,
                contentDescription = "Call ${contact.displayName}",
                tint               = AccentBlue,
                modifier           = Modifier.size(20.dp),
            )
        }
    }
}

// =============================================================================
// DialerKeypad — 4 × 3 grid with speed-dial long-press
// =============================================================================

/** Keypad rows: each entry is main-label to sub-label (letters). */
private val KEYPAD_ROWS = listOf(
    listOf("1" to "",     "2" to "ABC",  "3" to "DEF"),
    listOf("4" to "GHI",  "5" to "JKL",  "6" to "MNO"),
    listOf("7" to "PQRS", "8" to "TUV",  "9" to "WXYZ"),
    listOf("*" to "",     "0" to "+",    "#" to ""),
)

@Composable
private fun DialerKeypad(
    speedDialMap: Map<Int, String>,
    onDigit: (Char) -> Unit,
    onSpeedDialLong: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier            = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KEYPAD_ROWS.forEach { row ->
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { (digit, subLabel) ->
                    val slot            = digit.toIntOrNull()
                    val isSpeedDialSlot = slot != null && slot in 1..9
                    val isAssigned      = isSpeedDialSlot && speedDialMap.containsKey(slot)

                    DialerKeypadButton(
                        mainLabel          = digit,
                        subLabel           = subLabel,
                        isSpeedDialAssigned = isAssigned,
                        onClick            = {
                            // '0' single-tap produces '0'; long-press produces '+'
                            onDigit(digit[0])
                        },
                        onLongClick        = when {
                            isSpeedDialSlot -> { { onSpeedDialLong(slot!!) } }
                            digit == "0"    -> { { onDigit('+') } }
                            else            -> null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DialerKeypadButton(
    mainLabel: String,
    subLabel: String,
    isSpeedDialAssigned: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier         = modifier.size(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier         = Modifier
                .size(68.dp)
                .background(BgButton, CircleShape)
                .combinedClickable(
                    onClick     = onClick,
                    onLongClick = onLongClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text       = mainLabel,
                    style      = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White,
                )
                if (subLabel.isNotEmpty()) {
                    Text(
                        text          = subLabel,
                        style         = MaterialTheme.typography.labelSmall,
                        color         = TextSub,
                        letterSpacing = 1.sp,
                    )
                }
            }
        }

        // Small blue dot indicates an assigned speed-dial slot
        if (isSpeedDialAssigned) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 2.dp)
                    .background(AccentBlue, CircleShape),
            )
        }
    }
}

// =============================================================================
// SpeedDialDialog — call / assign / reassign / clear
// =============================================================================

@Composable
private fun SpeedDialDialog(
    slot: Int,
    currentAssignment: String?,
    hasCurrentDialedNumber: Boolean,
    onDismiss: () -> Unit,
    onCall: (String) -> Unit,
    onAssignCurrent: () -> Unit,
    onClear: () -> Unit,
) {
    val title = if (SpeedDialHelper.isVoicemailSlot(slot))
        "Speed Dial 1 — Voicemail"
    else
        "Speed Dial $slot"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = BgSurface,
        title = {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            val bodyText = when {
                currentAssignment != null -> "Assigned: $currentAssignment"
                hasCurrentDialedNumber    -> "No number assigned. Assign the current number?"
                else                      ->
                    "Slot is empty. Type a number in the keypad first, then long-press to assign."
            }
            Text(bodyText, color = TextSub, style = MaterialTheme.typography.bodySmall)
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // ── Call the assigned number
                if (currentAssignment != null) {
                    TextButton(onClick = { onCall(currentAssignment) }) {
                        Text("Call $currentAssignment", color = AccentGreen)
                    }
                }
                // ── Assign / reassign with what is currently typed in the keypad
                if (hasCurrentDialedNumber) {
                    TextButton(onClick = { onAssignCurrent(); onDismiss() }) {
                        Text(
                            text  = if (currentAssignment == null) "Assign current number"
                                    else "Reassign to current number",
                            color = AccentBlue,
                        )
                    }
                }
                // ── Remove existing assignment
                if (currentAssignment != null) {
                    TextButton(onClick = { onClear() }) {
                        Text("Remove assignment", color = Color(0xFFFF4757))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSub)
            }
        },
    )
}

// =============================================================================
// SimPickerDialog — shown when the device has more than one active SIM
// =============================================================================

@Composable
private fun SimPickerDialog(
    simList: List<SimSelectionHelper.SimInfo>,
    onSelect: (PhoneAccountHandle?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = BgSurface,
        title = {
            Text("Choose SIM", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                simList.forEach { sim ->
                    Row(
                        modifier              = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BgButton)
                            .clickable { onSelect(sim.phoneAccountHandle) }
                            .padding(12.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text       = "SIM ${sim.slotIndex + 1}",
                            color      = AccentBlue,
                            fontWeight = FontWeight.Bold,
                        )
                        Column {
                            Text(
                                sim.displayName,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (sim.phoneNumber.isNotBlank()) {
                                Text(
                                    sim.phoneNumber,
                                    color = TextSub,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton  = {},
        dismissButton  = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSub)
            }
        },
    )
}
