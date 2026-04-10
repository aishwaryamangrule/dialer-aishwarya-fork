package com.fayyaztech.dialer_core.ui.contacts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.fayyaztech.dialer_core.services.ContactsHelper
import com.fayyaztech.dialer_core.services.SimContact
import com.fayyaztech.dialer_core.services.SimSelectionHelper
import com.fayyaztech.dialer_core.ui.theme.DefaultDialerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen contacts browser implementing:
 *
 * - **All tab** — alphabetically grouped list of all account contacts with A-Z section headers.
 *   Account filter chips let the user show only Google / Exchange / Phone contacts.
 * - **Favourites tab** — starred contacts.
 * - **SIM tab** — contacts read directly from the ICC/ADN store with an "Import All" button.
 * - **Search** — inline text field filters the active tab's list.
 * - **FAB** ("+") — opens [ContactDetailActivity] in create mode.
 * - Tapping a contact row opens [ContactDetailActivity] in view mode for that contact.
 */
class ContactsActivity : ComponentActivity() {

    private val requiredPerms = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
    )

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* refresh handled by LaunchedEffect permission check */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DefaultDialerTheme {
                ContactsScreen(
                    onNavigateUp          = { finish() },
                    onRequestPermissions  = { permLauncher.launch(requiredPerms) },
                    onOpenContact         = { id -> openContactDetail(id) },
                    onCreateContact       = { openContactDetail(null) },
                )
            }
        }
    }

    private fun openContactDetail(contactId: Long?) {
        val intent = Intent(this, ContactDetailActivity::class.java)
        if (contactId != null) {
            intent.putExtra(ContactDetailActivity.EXTRA_CONTACT_ID, contactId)
        }
        startActivity(intent)
    }
}

// =============================================================================
// Screen root
// =============================================================================

private enum class ContactTab(val label: String) { ALL("All"), FAVORITES("Favourites"), SIM("SIM") }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ContactsScreen(
    onNavigateUp         : () -> Unit,
    onRequestPermissions : () -> Unit,
    onOpenContact        : (Long) -> Unit,
    onCreateContact      : () -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // ── State ──────────────────────────────────────────────────────────────────
    var selectedTab      by remember { mutableIntStateOf(0) }
    var searchQuery      by remember { mutableStateOf("") }
    var selectedAccount  by remember { mutableStateOf<ContactAccount?>(null) }
    var isLoading        by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importCount      by remember { mutableIntStateOf(0) }

    val allContacts  = remember { mutableStateListOf<ContactDetail>() }
    val favContacts  = remember { mutableStateListOf<ContactDetail>() }
    val simContacts  = remember { mutableStateListOf<SimContact>() }
    val accounts     = remember { mutableStateListOf<ContactAccount>() }

    val hasPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS,
    ) == PackageManager.PERMISSION_GRANTED

    // ── Load data ──────────────────────────────────────────────────────────────
    LaunchedEffect(selectedTab, searchQuery, selectedAccount, hasPermission) {
        if (!hasPermission) { onRequestPermissions(); return@LaunchedEffect }
        isLoading = true
        val q = searchQuery.trim().ifEmpty { null }
        when (ContactTab.entries[selectedTab]) {
            ContactTab.ALL -> {
                val loaded = withContext(Dispatchers.IO) {
                    ContactsHelper.getAllContacts(
                        context,
                        accountType = selectedAccount?.type,
                        nameQuery   = q,
                    )
                }
                allContacts.clear(); allContacts.addAll(loaded)
                // Load accounts once
                if (accounts.isEmpty()) {
                    val accs = withContext(Dispatchers.IO) { ContactsHelper.getSyncAccounts(context) }
                    accounts.clear(); accounts.addAll(accs)
                }
            }
            ContactTab.FAVORITES -> {
                val loaded = withContext(Dispatchers.IO) {
                    ContactsHelper.getStarredContacts(context).let { list ->
                        if (q != null) list.filter { it.displayName.contains(q, ignoreCase = true) }
                        else list
                    }
                }
                favContacts.clear(); favContacts.addAll(loaded)
            }
            ContactTab.SIM -> {
                val sims = SimSelectionHelper.getAvailableSims(context)
                val loaded = withContext(Dispatchers.IO) {
                    if (sims.isNotEmpty()) {
                        sims.flatMap { sim ->
                            ContactsHelper.getSimContacts(context, sim.subscriptionId)
                        }.distinctBy { it.number }
                    } else {
                        ContactsHelper.getSimContacts(context)
                    }.let { list ->
                        if (q != null) list.filter {
                            it.name.contains(q, ignoreCase = true) ||
                            it.number.contains(q)
                        } else list
                    }
                }
                simContacts.clear(); simContacts.addAll(loaded)
            }
        }
        isLoading = false
    }

    // ── UI ─────────────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Contacts") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor    = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
                // Search bar
                OutlinedTextField(
                    value         = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier      = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder   = { Text("Search contacts") },
                    leadingIcon   = { Icon(Icons.Filled.Search, null) },
                    trailingIcon  = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, "Clear")
                            }
                        }
                    },
                    singleLine    = true,
                    shape         = RoundedCornerShape(24.dp),
                )
                Spacer(Modifier.height(4.dp))
                // Tab row
                TabRow(selectedTabIndex = selectedTab) {
                    ContactTab.entries.forEachIndexed { idx, tab ->
                        Tab(
                            selected = selectedTab == idx,
                            onClick  = { selectedTab = idx; searchQuery = "" },
                            text     = { Text(tab.label) },
                            icon     = {
                                when (tab) {
                                    ContactTab.ALL       -> Icon(Icons.Filled.Person, null, Modifier.size(16.dp))
                                    ContactTab.FAVORITES -> Icon(Icons.Filled.Star, null, Modifier.size(16.dp))
                                    ContactTab.SIM       -> Icon(Icons.Filled.SimCard, null, Modifier.size(16.dp))
                                }
                            },
                        )
                    }
                }
                // Account filter chips (All tab only)
                if (selectedTab == 0 && accounts.size > 1) {
                    LazyRow(
                        modifier          = Modifier.fillMaxWidth(),
                        contentPadding    = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = selectedAccount == null,
                                onClick  = { selectedAccount = null },
                                label    = { Text("All accounts") },
                            )
                        }
                        items(accounts) { acc ->
                            FilterChip(
                                selected = selectedAccount?.type == acc.type,
                                onClick  = {
                                    selectedAccount = if (selectedAccount?.type == acc.type) null else acc
                                },
                                label    = { Text(acc.label) },
                                colors   = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor     = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateContact) {
                Icon(Icons.Filled.Add, "New contact")
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                when (ContactTab.entries[selectedTab]) {

                    ContactTab.ALL -> {
                        if (!hasPermission) {
                            PermissionEmptyState(onRequestPermissions)
                        } else if (allContacts.isEmpty()) {
                            EmptyState("No contacts found")
                        } else {
                            AlphabeticContactList(
                                contacts      = allContacts,
                                onContactClick = onOpenContact,
                            )
                        }
                    }

                    ContactTab.FAVORITES -> {
                        if (!hasPermission) {
                            PermissionEmptyState(onRequestPermissions)
                        } else if (favContacts.isEmpty()) {
                            EmptyState("No favourite contacts.\nStar a contact to add it here.")
                        } else {
                            ContactList(contacts = favContacts, onContactClick = onOpenContact)
                        }
                    }

                    ContactTab.SIM -> {
                        if (simContacts.isEmpty()) {
                            EmptyState("No SIM contacts found")
                        } else {
                            SimContactList(
                                contacts      = simContacts,
                                onImportAll   = { showImportDialog = true },
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Import confirm dialog ──────────────────────────────────────────────────
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title   = { Text("Import SIM contacts") },
            text    = { Text("Import all ${simContacts.size} SIM contacts to the phone?") },
            confirmButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    scope.launch {
                        val count = withContext(Dispatchers.IO) {
                            ContactsHelper.importSimContacts(context, simContacts)
                        }
                        importCount = count
                        Toast.makeText(context, "$count contacts imported", Toast.LENGTH_SHORT).show()
                        // Switch to All tab to see imported contacts
                        selectedTab = 0
                    }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            },
        )
    }
}

// =============================================================================
// Alphabetically-grouped contact list (All tab)
// =============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlphabeticContactList(
    contacts      : List<ContactDetail>,
    onContactClick: (Long) -> Unit,
) {
    // Group by first letter of display name
    val grouped = contacts.groupBy { c ->
        c.displayName.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() }?.toString() ?: "#"
    }.toSortedMap()

    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp),
    ) {
        grouped.forEach { (letter, group) ->
            stickyHeader(key = letter) {
                Text(
                    text     = letter,
                    style    = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color    = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(group, key = { it.contactId }) { contact ->
                ContactRow(contact = contact, onClick = { onContactClick(contact.contactId) })
                HorizontalDivider(
                    modifier  = Modifier.padding(start = 72.dp),
                    thickness = 0.5.dp,
                    color     = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

// =============================================================================
// Plain contact list (Favourites tab)
// =============================================================================

@Composable
private fun ContactList(
    contacts      : List<ContactDetail>,
    onContactClick: (Long) -> Unit,
) {
    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp),
    ) {
        items(contacts, key = { it.contactId }) { contact ->
            ContactRow(contact = contact, onClick = { onContactClick(contact.contactId) })
            HorizontalDivider(
                modifier  = Modifier.padding(start = 72.dp),
                thickness = 0.5.dp,
                color     = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

// =============================================================================
// SIM contact list (SIM tab)
// =============================================================================

@Composable
private fun SimContactList(
    contacts  : List<SimContact>,
    onImportAll: () -> Unit,
) {
    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp),
    ) {
        item {
            TextButton(
                onClick  = onImportAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Import all ${contacts.size} SIM contacts to Phone")
            }
            HorizontalDivider()
        }
        items(contacts, key = { "${it.name}_${it.number}" }) { sim ->
            SimContactRow(contact = sim)
            HorizontalDivider(
                modifier  = Modifier.padding(start = 72.dp),
                thickness = 0.5.dp,
                color     = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

// =============================================================================
// Row composables
// =============================================================================

@Composable
private fun ContactRow(contact: ContactDetail, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar circle with initial
        ContactAvatar(name = contact.displayName, size = 44)

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text      = contact.displayName.ifBlank { contact.phones.firstOrNull()?.number ?: "?" },
                style     = MaterialTheme.typography.bodyLarge,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
            )
            val subtitle = contact.phones.firstOrNull()?.let { "${it.typeLabel}  ${it.number}" }
            if (subtitle != null) {
                Text(
                    text  = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (contact.isStarred) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Favourite",
                tint     = Color(0xFFFFC107),
                modifier = Modifier.size(16.dp),
            )
        }
        // Account badge
        val acctLabel = when (contact.accountType) {
            "com.google"           -> "G"
            "com.android.exchange" -> "E"
            null                   -> ""
            else                   -> ""
        }
        if (acctLabel.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Text(
                text  = acctLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun SimContactRow(contact: SimContact) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier         = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xFF546E7A).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.SimCard, null, tint = Color(0xFF546E7A), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text     = contact.name.ifBlank { contact.number },
                style    = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (contact.name.isNotBlank()) {
                Text(
                    text  = contact.number,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// =============================================================================
// Avatar circle
// =============================================================================

@Composable
private fun ContactAvatar(name: String, size: Int) {
    val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val hue     = ((name.hashCode() and 0xFF) * 360f / 256f)
    val bgColor = Color.hsl(hue, 0.45f, 0.35f)

    Box(
        modifier         = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = initial,
            color      = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize   = (size * 0.42f).sp,
        )
    }
}

// =============================================================================
// Empty states
// =============================================================================

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text  = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionEmptyState(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Contacts permission required", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRequest) { Text("Grant permission") }
        }
    }
}
