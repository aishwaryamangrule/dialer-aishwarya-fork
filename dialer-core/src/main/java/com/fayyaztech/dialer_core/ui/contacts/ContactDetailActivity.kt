package com.fayyaztech.dialer_core.ui.contacts

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.fayyaztech.dialer_core.services.ContactAccount
import com.fayyaztech.dialer_core.services.ContactDetail
import com.fayyaztech.dialer_core.services.ContactPhone
import com.fayyaztech.dialer_core.services.ContactsHelper
import com.fayyaztech.dialer_core.services.DuplicateGroup
import com.fayyaztech.dialer_core.services.SimPreferenceHelper
import com.fayyaztech.dialer_core.services.SimSelectionHelper
import com.fayyaztech.dialer_core.ui.dialer.DialerActivity
import com.fayyaztech.dialer_core.ui.theme.DefaultDialerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows and edits a single contact.
 *
 * ## Modes
 * - **View mode** (launched with [EXTRA_CONTACT_ID]): shows all phone numbers, each with
 *   Call / Message / Copy actions. Star/unstar toggle. Edit and Delete in the top bar.
 *   If [ContactsHelper.findDuplicates] finds another contact sharing a number, a banner
 *   proposes a merge.
 * - **Edit mode** (view → tap "Edit"): inline TextFields for name and per-phone-row editor.
 * - **Create mode** (launched without [EXTRA_CONTACT_ID]): blank form, account selector.
 *
 * ## Quick dial from contact card
 * Every phone-number row contains a green Call button (→ `SimSelectionHelper.placeCall()`).
 * This is the "quick dial from contact card" feature.
 */
class ContactDetailActivity : ComponentActivity() {

    companion object {
        /** Long extra — the contact ID to display. Omit to open in create mode. */
        const val EXTRA_CONTACT_ID  = "extra_contact_id"
        const val EXTRA_ACCOUNT_TYPE = "extra_account_type"
        const val EXTRA_ACCOUNT_NAME = "extra_account_name"
    }

    private val writePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* refreshed by LaunchedEffect */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val contactId    = intent.getLongExtra(EXTRA_CONTACT_ID, -1L).takeIf { it > 0 }
        val accountType  = intent.getStringExtra(EXTRA_ACCOUNT_TYPE)
        val accountName  = intent.getStringExtra(EXTRA_ACCOUNT_NAME)
        val initialAccount = if (accountType != null || accountName != null)
            ContactAccount(name = accountName ?: "", type = accountType)
        else null

        setContent {
            DefaultDialerTheme {
                ContactDetailScreen(
                    contactId       = contactId,
                    initialAccount  = initialAccount,
                    onNavigateUp    = { finish() },
                    onRequestWrite  = { writePermLauncher.launch(Manifest.permission.WRITE_CONTACTS) },
                )
            }
        }
    }
}

// =============================================================================
// Screen root
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactDetailScreen(
    contactId      : Long?,
    initialAccount : ContactAccount?,
    onNavigateUp   : () -> Unit,
    onRequestWrite : () -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // ── Mode ───────────────────────────────────────────────────────────────────
    var isEditMode  by remember { mutableStateOf(contactId == null) } // create mode = edit immediately
    var isLoading   by remember { mutableStateOf(contactId != null) }

    // ── Data ────────────────────────────────────────────────────────────────────
    var contact     by remember { mutableStateOf<ContactDetail?>(null) }
    var duplicates  by remember { mutableStateOf<List<DuplicateGroup>>(emptyList()) }
    var accounts    by remember { mutableStateOf<List<ContactAccount>>(emptyList()) }

    // ── Edit state ─────────────────────────────────────────────────────────────
    var editName        by remember { mutableStateOf("") }
    val editPhones      = remember { mutableStateListOf<EditPhone>() }
    var selectedAccount by remember { mutableStateOf<ContactAccount?>(initialAccount) }

    // ── Dialogs ────────────────────────────────────────────────────────────────
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var mergeTarget      by remember { mutableStateOf<DuplicateGroup?>(null) }
    var showAccountPicker by remember { mutableStateOf(false) }
    var showMoreMenu     by remember { mutableStateOf(false) }

    // ── SIM preference (view mode) ─────────────────────────────────────────────
    var simList          by remember { mutableStateOf<List<SimSelectionHelper.SimInfo>>(emptyList()) }
    var preferredSimSubId by remember { mutableStateOf(-1) }
    var showSimPrefPicker by remember { mutableStateOf(false) }

    // ── Load contact ───────────────────────────────────────────────────────────
    LaunchedEffect(contactId) {
        // Load SIM list once (cheap — just reads system state)
        simList = withContext(Dispatchers.IO) { SimSelectionHelper.getAvailableSims(context) }

        if (contactId == null) {
            isLoading = false
            accounts  = withContext(Dispatchers.IO) { ContactsHelper.getSyncAccounts(context) }
            if (selectedAccount == null) selectedAccount = accounts.firstOrNull()
            return@LaunchedEffect
        }
        preferredSimSubId = SimPreferenceHelper.getContactSimSubId(context, contactId)
        isLoading = true
        val loaded = withContext(Dispatchers.IO) { ContactsHelper.getContactDetail(context, contactId) }
        contact = loaded
        loaded?.let { c ->
            editName   = c.displayName
            editPhones.clear()
            editPhones.addAll(c.phones.map { EditPhone(it.number, it.typeCode, it.typeLabel) })
            // Find duplicates
            val dupes = withContext(Dispatchers.IO) { ContactsHelper.findDuplicates(context) }
            duplicates = dupes.filter {
                it.primaryContactId == contactId || it.secondaryContactId == contactId
            }
        }
        accounts  = withContext(Dispatchers.IO) { ContactsHelper.getSyncAccounts(context) }
        isLoading = false
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    fun enterEditMode() {
        if (contact != null) {
            editName = contact!!.displayName
            editPhones.clear()
            editPhones.addAll(contact!!.phones.map { EditPhone(it.number, it.typeCode, it.typeLabel) })
        }
        isEditMode = true
    }

    fun saveContact() {
        val phones = editPhones.map { ep ->
            ContactPhone(
                dataId          = 0,
                number          = ep.number,
                normalizedNumber = ep.number,
                typeCode        = ep.typeCode,
                typeLabel       = ep.typeLabel,
            )
        }.filter { it.number.isNotBlank() }

        if (editName.isBlank() && phones.isEmpty()) {
            Toast.makeText(context, "Name or phone number required", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            val success = withContext(Dispatchers.IO) {
                if (contactId == null) {
                    ContactsHelper.createContact(context, selectedAccount, editName, phones) > 0
                } else {
                    ContactsHelper.updateContact(context, contactId, editName, phones)
                }
            }
            if (success) {
                Toast.makeText(context, if (contactId == null) "Contact created" else "Contact saved", Toast.LENGTH_SHORT).show()
                if (contactId == null) {
                    onNavigateUp()
                } else {
                    // Refresh
                    val refreshed = withContext(Dispatchers.IO) { ContactsHelper.getContactDetail(context, contactId) }
                    contact = refreshed
                    isEditMode = false
                }
            } else {
                Toast.makeText(context, "Failed to save contact", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val hasWritePermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.WRITE_CONTACTS,
    ) == PackageManager.PERMISSION_GRANTED

    // ── Scaffold ───────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (contactId == null) "New Contact" else if (isEditMode) "Edit Contact" else "Contact")
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isEditMode && contactId != null) isEditMode = false else onNavigateUp()
                    }) {
                        Icon(
                            if (isEditMode && contactId != null) Icons.Filled.Close
                            else Icons.AutoMirrored.Filled.ArrowBack,
                            "Back",
                        )
                    }
                },
                actions = {
                    if (isEditMode) {
                        IconButton(onClick = { saveContact() }) {
                            Icon(Icons.Filled.Save, "Save")
                        }
                    } else {
                        // Star toggle
                        contact?.let { c ->
                            IconButton(onClick = {
                                if (!hasWritePermission) { onRequestWrite(); return@IconButton }
                                scope.launch {
                                    val newVal = !c.isStarred
                                    withContext(Dispatchers.IO) {
                                        ContactsHelper.setStarred(context, c.contactId, newVal)
                                    }
                                    contact = c.copy(isStarred = newVal)
                                }
                            }) {
                                Icon(
                                    if (c.isStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
                                    "Favourite",
                                    tint = if (c.isStarred) Color(0xFFFFC107)
                                           else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            // Edit
                            IconButton(onClick = { enterEditMode() }) {
                                Icon(Icons.Filled.Edit, "Edit")
                            }
                            // Overflow → Delete
                            Box {
                                IconButton(onClick = { showMoreMenu = true }) {
                                    Icon(Icons.Filled.Delete, "Delete")
                                }
                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text    = { Text("Delete contact") },
                                        onClick = { showMoreMenu = false; showDeleteConfirm = true },
                                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier       = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        ) {

            // ── Avatar + name ──────────────────────────────────────────────────
            item {
                Column(
                    modifier            = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Avatar
                    val name   = if (isEditMode) editName else contact?.displayName ?: ""
                    val hue    = ((name.hashCode() and 0xFF) * 360f / 256f)
                    val bgClr  = Color.hsl(hue, 0.45f, 0.35f)
                    Box(
                        modifier         = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(bgClr),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text       = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            color      = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize   = 40.sp,
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Name field (edit) or text (view)
                    if (isEditMode) {
                        OutlinedTextField(
                            value         = editName,
                            onValueChange = { editName = it },
                            label         = { Text("Name") },
                            singleLine    = true,
                            modifier      = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                        )
                    } else {
                        Text(
                            text       = contact?.displayName ?: "",
                            style      = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        // Account label
                        val acctLabel = contact?.accountType?.let {
                            ContactAccount(name = contact?.accountName ?: "", type = it).label
                        } ?: "Phone"
                        Text(
                            text  = acctLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── Account picker (create mode) ──────────────────────────────────
            if (isEditMode && contactId == null) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text("Save to account", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        accounts.forEach { acc ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedAccount = acc }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = selectedAccount?.type == acc.type,
                                    onClick  = { selectedAccount = acc },
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(acc.label, style = MaterialTheme.typography.bodyMedium)
                                    if (acc.name.isNotBlank()) {
                                        Text(acc.name, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }

            // ── Phone numbers section header ───────────────────────────────────
            item {
                Text(
                    text     = "Phone numbers",
                    style    = MaterialTheme.typography.labelMedium,
                    color    = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // ── Phone number rows ──────────────────────────────────────────────
            if (isEditMode) {
                items(editPhones.size) { idx ->
                    EditPhoneRow(
                        editPhone  = editPhones[idx],
                        onChanged  = { updated -> editPhones[idx] = updated },
                        onRemove   = { editPhones.removeAt(idx) },
                    )
                }
                // Add phone button
                item {
                    TextButton(
                        onClick  = {
                            editPhones += EditPhone(
                                number   = "",
                                typeCode  = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                                typeLabel = "Mobile",
                            )
                        },
                        modifier = Modifier.padding(start = 12.dp),
                    ) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add phone number")
                    }
                }
            } else {
                // View mode: phone rows with Call / Message / Copy actions
                val phones = contact?.phones ?: emptyList()
                if (phones.isEmpty()) {
                    item {
                        Text(
                            "No phone numbers",
                            style    = MaterialTheme.typography.bodyMedium,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    items(phones, key = { it.dataId }) { phone ->
                        ViewPhoneRow(
                            phone            = phone,
                            context          = context,
                        )
                        HorizontalDivider(
                            modifier  = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color     = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }

            // ── Preferred SIM section (view mode, multi-SIM only) ─────────────
            if (!isEditMode && contactId != null && simList.size > 1) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text     = "Preferred SIM",
                        style    = MaterialTheme.typography.labelMedium,
                        color    = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    val currentSim = simList.find { it.subscriptionId == preferredSimSubId }
                    val label = currentSim?.let { "SIM ${it.slotIndex + 1}: ${it.displayName}" }
                        ?: "None – ask each time"
                    Row(
                        modifier            = Modifier
                            .fillMaxWidth()
                            .clickable { showSimPrefPicker = true }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment   = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text("Change", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium)
                    }
                    HorizontalDivider(
                        modifier  = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color     = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }

            // ── Duplicate merge suggestion ─────────────────────────────────────
            if (!isEditMode && duplicates.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors   = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Possible duplicate contact",
                                style      = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color      = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            duplicates.forEach { dup ->
                                val otherName = if (dup.primaryContactId == contactId)
                                    dup.secondaryName else dup.primaryName
                                val otherId   = if (dup.primaryContactId == contactId)
                                    dup.secondaryContactId else dup.primaryContactId
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "\"$otherName\" shares ${dup.sharedNumber}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                TextButton(
                                    onClick = {
                                        if (!hasWritePermission) { onRequestWrite(); return@TextButton }
                                        scope.launch {
                                            val ok = withContext(Dispatchers.IO) {
                                                ContactsHelper.mergeContacts(
                                                    context, dup.primaryContactId, dup.secondaryContactId,
                                                )
                                            }
                                            if (ok) {
                                                Toast.makeText(context, "Contacts merged", Toast.LENGTH_SHORT).show()
                                                duplicates = duplicates - dup
                                            }
                                        }
                                    },
                                ) {
                                    Icon(Icons.Filled.MergeType, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Merge with \"$otherName\"")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Delete confirm ─────────────────────────────────────────────────────────
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title   = { Text("Delete contact?") },
            text    = { Text("\"${contact?.displayName}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    contact?.let { c ->
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                ContactsHelper.deleteContact(context, c.contactId)
                            }
                            if (ok) {
                                Toast.makeText(context, "Contact deleted", Toast.LENGTH_SHORT).show()
                                onNavigateUp()
                            } else {
                                Toast.makeText(context, "Delete failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }

    // ── SIM preference picker ─────────────────────────────────────────────────
    if (showSimPrefPicker && contactId != null) {
        AlertDialog(
            onDismissRequest = { showSimPrefPicker = false },
            title   = { Text("Preferred SIM") },
            text    = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // "None" option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                SimPreferenceHelper.setContactSimSubId(context, contactId, -1)
                                preferredSimSubId  = -1
                                showSimPrefPicker  = false
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = preferredSimSubId < 0,
                            onClick  = null,
                        )
                        Text("None – ask each time")
                    }
                    simList.forEach { sim ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    SimPreferenceHelper.setContactSimSubId(context, contactId, sim.subscriptionId)
                                    preferredSimSubId = sim.subscriptionId
                                    showSimPrefPicker = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = preferredSimSubId == sim.subscriptionId,
                                onClick  = null,
                            )
                            Column {
                                Text(
                                    "SIM ${sim.slotIndex + 1}: ${sim.displayName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (sim.phoneNumber.isNotBlank()) {
                                    Text(
                                        sim.phoneNumber,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSimPrefPicker = false }) { Text("Cancel") }
            },
        )
    }
}

// =============================================================================
// View-mode phone row — quick dial from contact card
// =============================================================================

/**
 * Displays a single phone number in view mode with three action buttons:
 *  - **Call** (green) — opens [DialerActivity] in auto-call mode so the unified SIM policy
 *    (per-contact preference, ask-each-time, default SIM, roaming warning) is applied.
 *  - **Message** — opens the system SMS app with the number pre-filled.
 *  - **Copy** — copies the number to the clipboard.
 */
@Composable
private fun ViewPhoneRow(
    phone: ContactPhone,
    context: Context,
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text  = phone.typeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (phone.isPrimary) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Default",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text  = phone.number,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ── Call button (Quick Dial from Contact Card) ─────────────────────────
        IconButton(onClick = {
            // Route through DialerActivity so all SIM rules are applied in one place.
            val intent = Intent(context, DialerActivity::class.java).apply {
                putExtra("PHONE_NUMBER", phone.number)
                putExtra(DialerActivity.EXTRA_AUTO_PLACE_CALL, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }) {
            Icon(
                Icons.Filled.Call,
                contentDescription = "Call ${phone.number}",
                tint               = Color(0xFF4CAF50),
            )
        }

        // ── Message button ─────────────────────────────────────────────────────
        IconButton(onClick = {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${phone.number}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try { context.startActivity(intent) }
            catch (_: Exception) {
                Toast.makeText(context, "No messaging app found", Toast.LENGTH_SHORT).show()
            }
        }) {
            Icon(Icons.AutoMirrored.Filled.Message, "Message")
        }

        // ── Copy button ────────────────────────────────────────────────────────
        IconButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Phone number", phone.number))
            Toast.makeText(context, "Number copied", Toast.LENGTH_SHORT).show()
        }) {
            Icon(Icons.Filled.ContentCopy, "Copy number")
        }
    }
}

// =============================================================================
// Edit-mode phone row
// =============================================================================

/** Transient state for one phone row in edit mode. */
data class EditPhone(val number: String, val typeCode: Int, val typeLabel: String)

private val PHONE_TYPES = listOf(
    Triple(ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, "Mobile", "Mobile"),
    Triple(ContactsContract.CommonDataKinds.Phone.TYPE_HOME,   "Home",   "Home"),
    Triple(ContactsContract.CommonDataKinds.Phone.TYPE_WORK,   "Work",   "Work"),
    Triple(ContactsContract.CommonDataKinds.Phone.TYPE_OTHER,  "Other",  "Other"),
)

@Composable
private fun EditPhoneRow(
    editPhone : EditPhone,
    onChanged : (EditPhone) -> Unit,
    onRemove  : () -> Unit,
) {
    var showTypeMenu by remember { mutableStateOf(false) }

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Type label as a clickable chip
        Box {
            TextButton(onClick = { showTypeMenu = true }, shape = RoundedCornerShape(8.dp)) {
                Text(
                    text  = editPhone.typeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            DropdownMenu(
                expanded         = showTypeMenu,
                onDismissRequest = { showTypeMenu = false },
            ) {
                PHONE_TYPES.forEach { (code, label, _) ->
                    DropdownMenuItem(
                        text    = { Text(label) },
                        onClick = {
                            onChanged(editPhone.copy(typeCode = code, typeLabel = label))
                            showTypeMenu = false
                        },
                    )
                }
            }
        }

        // Number text field
        OutlinedTextField(
            value         = editPhone.number,
            onValueChange = { onChanged(editPhone.copy(number = it)) },
            modifier      = Modifier.weight(1f),
            singleLine    = true,
            placeholder   = { Text("Phone number") },
        )

        // Remove button
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Remove, "Remove phone", tint = MaterialTheme.colorScheme.error)
        }
    }
}
