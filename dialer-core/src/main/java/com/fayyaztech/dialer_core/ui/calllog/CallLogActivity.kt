package com.fayyaztech.dialer_core.ui.calllog

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.fayyaztech.dialer_core.services.CallLogEntry
import com.fayyaztech.dialer_core.services.CallLogFilter
import com.fayyaztech.dialer_core.services.CallLogHelper
import com.fayyaztech.dialer_core.services.CallType
import com.fayyaztech.dialer_core.ui.theme.DefaultDialerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Intent extra key: pass a [CallType.code] int to pre-select a filter tab.
 *
 * Example — open on Missed tab from a missed-call notification:
 * ```kotlin
 * Intent(context, CallLogActivity::class.java)
 *     .putExtra(CallLogActivity.EXTRA_FILTER_TYPE, CallType.MISSED.code)
 * ```
 */
const val EXTRA_FILTER_TYPE = "extra_filter_type"

/**
 * Full-screen call-history UI.
 *
 * ## Features
 * - Filter by call type (All / Incoming / Outgoing / Missed / Rejected / Blocked)
 * - Filter by SIM label (dynamic chip, shown only when multiple SIMs present in the log)
 * - Filter by date range (start / end date pickers)
 * - Filter by contact name or phone number (intent-level)
 * - Long-press an entry to enter selection mode; bulk-delete selected entries
 * - Tap an entry to show actions: Call Back, Send SMS, Copy Number
 * - Marks missed calls as read on open and cancels missed-call notifications
 */
class CallLogActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG,
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* UI refreshes automatically via LaunchedEffect on permissions */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialTypeCode = intent.getIntExtra(EXTRA_FILTER_TYPE, 0)

        setContent {
            DefaultDialerTheme {
                CallLogScreen(
                    initialTypeCode = initialTypeCode,
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
                    onNavigateUp = { finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Dismiss any lingering missed-call notifications when the user opens the log.
        cancelMissedCallNotifications()
    }

    private fun cancelMissedCallNotifications() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(/* missed call notification id */ 1001)
    }
}

// =============================================================================
// Screen root
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallLogScreen(
    initialTypeCode: Int,
    onRequestPermissions: () -> Unit,
    onNavigateUp: () -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // ---- State ---------------------------------------------------------------
    val entries     = remember { mutableStateListOf<CallLogEntry>() }
    val selectedIds = remember { mutableStateListOf<Long>() }
    val isSelectionMode by remember { derivedStateOf { selectedIds.isNotEmpty() } }

    var selectedType  by remember { mutableStateOf(typeFromCode(initialTypeCode)) }
    var selectedSim   by remember { mutableStateOf<String?>(null) }
    var fromMs        by remember { mutableStateOf<Long?>(null) }
    var toMs          by remember { mutableStateOf<Long?>(null) }
    var hasPermission by remember {
        mutableStateOf(
            requiredPermissionsGranted(context)
        )
    }
    var showDeleteConfirm  by remember { mutableStateOf(false) }
    var selectedEntry      by remember { mutableStateOf<CallLogEntry?>(null) }
    var showDateRangePicker by remember { mutableStateOf(false) }

    // ---- Load entries --------------------------------------------------------
    LaunchedEffect(selectedType, selectedSim, fromMs, toMs, hasPermission) {
        if (!hasPermission) {
            onRequestPermissions()
            return@LaunchedEffect
        }
        val filter = CallLogFilter(
            types    = if (selectedType == null) emptyList() else listOf(selectedType!!),
            simLabel = selectedSim,
            fromMs   = fromMs,
            toMs     = toMs,
        )
        val loaded = withContext(Dispatchers.IO) {
            CallLogHelper.query(context, filter).also {
                // Side effect: mark missed calls as read
                CallLogHelper.markAllMissedAsRead(context)
            }
        }
        entries.clear()
        entries.addAll(loaded)
        // Re-check permission flag after the attempt
        hasPermission = requiredPermissionsGranted(context)
    }

    // ---- Distinct SIM labels from loaded entries (for SIM chip row) ----------
    val distinctSims = remember(entries.toList()) {
        entries.mapNotNull { it.simLabel }.distinct().sorted()
    }

    // ---- UI ------------------------------------------------------------------
    Scaffold(
        topBar = {
            CallLogTopBar(
                isSelectionMode = isSelectionMode,
                selectionCount  = selectedIds.size,
                onNavigateUp    = {
                    if (isSelectionMode) selectedIds.clear() else onNavigateUp()
                },
                onDeleteSelected = { showDeleteConfirm = true },
                onFilterDate     = { showDateRangePicker = !showDateRangePicker },
                hasDateFilter    = fromMs != null || toMs != null,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Type filter chips
            TypeFilterRow(
                selected  = selectedType,
                onSelect  = { type ->
                    selectedType = if (selectedType == type) null else type
                    selectedIds.clear()
                },
            )

            // SIM filter chips (shown only when >1 SIM in log)
            if (distinctSims.size > 1) {
                SimFilterRow(
                    sims       = distinctSims,
                    selected   = selectedSim,
                    onSelect   = { sim ->
                        selectedSim = if (selectedSim == sim) null else sim
                        selectedIds.clear()
                    },
                )
            }

            // Date range summary
            if (fromMs != null || toMs != null) {
                DateRangeSummary(fromMs = fromMs, toMs = toMs, onClear = { fromMs = null; toMs = null })
            }

            if (entries.isEmpty()) {
                EmptyState(hasPermission = hasPermission, onRequestPermissions = onRequestPermissions)
            } else {
                CallLogList(
                    entries         = entries,
                    selectedIds     = selectedIds,
                    isSelectionMode = isSelectionMode,
                    onEntryClick    = { entry ->
                        if (isSelectionMode) {
                            toggleSelection(entry.id, selectedIds)
                        } else {
                            selectedEntry = entry
                        }
                    },
                    onEntryLongClick = { entry ->
                        toggleSelection(entry.id, selectedIds)
                    },
                )
            }
        }
    }

    // ---- Delete confirm dialog -----------------------------------------------
    if (showDeleteConfirm) {
        DeleteConfirmDialog(
            count     = selectedIds.size,
            onConfirm = {
                showDeleteConfirm = false
                val toDelete = selectedIds.toList()
                scope.launch(Dispatchers.IO) {
                    CallLogHelper.deleteEntries(context, toDelete)
                    val filter = CallLogFilter(
                        types    = if (selectedType == null) emptyList() else listOf(selectedType!!),
                        simLabel = selectedSim,
                        fromMs   = fromMs,
                        toMs     = toMs,
                    )
                    val refreshed = CallLogHelper.query(context, filter)
                    withContext(Dispatchers.Main) {
                        selectedIds.clear()
                        entries.clear()
                        entries.addAll(refreshed)
                    }
                }
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    // ---- Entry action bottom sheet -------------------------------------------
    selectedEntry?.let { entry ->
        EntryActionSheet(
            entry   = entry,
            context = context,
            onDismiss = { selectedEntry = null },
        )
    }

    // ---- Date range picker (simple from/to using epoch day dialogs) ----------
    if (showDateRangePicker) {
        DateRangeDialog(
            fromMs    = fromMs,
            toMs      = toMs,
            onApply   = { from, to -> fromMs = from; toMs = to; showDateRangePicker = false },
            onDismiss = { showDateRangePicker = false },
        )
    }
}

// =============================================================================
// Top app bar
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallLogTopBar(
    isSelectionMode : Boolean,
    selectionCount  : Int,
    onNavigateUp    : () -> Unit,
    onDeleteSelected: () -> Unit,
    onFilterDate    : () -> Unit,
    hasDateFilter   : Boolean,
) {
    TopAppBar(
        title = {
            Text(
                text = if (isSelectionMode) "$selectionCount selected" else "Call History",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        navigationIcon = {
            IconButton(onClick = onNavigateUp) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            if (isSelectionMode) {
                IconButton(onClick = onDeleteSelected) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
                }
            } else {
                IconButton(onClick = onFilterDate) {
                    Icon(
                        imageVector = Icons.Filled.FilterList,
                        contentDescription = "Filter by date",
                        tint = if (hasDateFilter) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor    = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

// =============================================================================
// Filter chip rows
// =============================================================================

private data class TypeChip(val label: String, val type: CallType?)
private val typeChips = listOf(
    TypeChip("All",      null),
    TypeChip("Incoming", CallType.INCOMING),
    TypeChip("Outgoing", CallType.OUTGOING),
    TypeChip("Missed",   CallType.MISSED),
    TypeChip("Rejected", CallType.REJECTED),
    TypeChip("Blocked",  CallType.BLOCKED),
)

@Composable
private fun TypeFilterRow(selected: CallType?, onSelect: (CallType?) -> Unit) {
    LazyRow(
        modifier            = Modifier.fillMaxWidth(),
        contentPadding      = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(typeChips) { chip ->
            val isSelected = selected == chip.type
            FilterChip(
                selected = isSelected,
                onClick  = { onSelect(chip.type) },
                label    = { Text(chip.label) },
                colors   = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor     = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun SimFilterRow(sims: List<String>, selected: String?, onSelect: (String) -> Unit) {
    LazyRow(
        modifier            = Modifier.fillMaxWidth(),
        contentPadding      = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sims) { sim ->
            FilterChip(
                selected = selected == sim,
                onClick  = { onSelect(sim) },
                label    = { Text(sim) },
                colors   = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor     = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            )
        }
    }
}

// =============================================================================
// Date range summary row
// =============================================================================

@Composable
private fun DateRangeSummary(fromMs: Long?, toMs: Long?, onClear: () -> Unit) {
    val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val fromStr = fromMs?.let { fmt.format(Date(it)) } ?: "—"
    val toStr   = toMs?.let   { fmt.format(Date(it)) } ?: "—"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = "Date: $fromStr → $toStr",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear) { Text("Clear") }
    }
}

// =============================================================================
// Call log list
// =============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallLogList(
    entries         : List<CallLogEntry>,
    selectedIds     : SnapshotStateList<Long>,
    isSelectionMode : Boolean,
    onEntryClick    : (CallLogEntry) -> Unit,
    onEntryLongClick: (CallLogEntry) -> Unit,
) {
    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        items(entries, key = { it.id }) { entry ->
            CallLogEntryRow(
                entry           = entry,
                isSelected      = entry.id in selectedIds,
                isSelectionMode = isSelectionMode,
                onClick         = { onEntryClick(entry) },
                onLongClick     = { onEntryLongClick(entry) },
            )
            HorizontalDivider(
                modifier  = Modifier.padding(start = 72.dp),
                thickness = 0.5.dp,
                color     = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallLogEntryRow(
    entry           : CallLogEntry,
    isSelected      : Boolean,
    isSelectionMode : Boolean,
    onClick         : () -> Unit,
    onLongClick     : () -> Unit,
) {
    val typeColor = callTypeColor(entry.callType)
    val typeIcon  = callTypeIcon(entry.callType)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Selection checkbox / type icon
        Box(
            modifier        = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelectionMode) {
                Icon(
                    imageVector        = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null,
                    tint               = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                    modifier           = Modifier.size(24.dp),
                )
            } else {
                Box(
                    modifier         = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(typeColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = typeIcon,
                        contentDescription = entry.callType.name,
                        tint               = typeColor,
                        modifier           = Modifier.size(20.dp),
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        // Name / number + timestamp
        Column(modifier = Modifier.weight(1f)) {
            val displayName = entry.contactName?.takeIf { it.isNotBlank() } ?: entry.phoneNumber
            Text(
                text       = displayName,
                style      = MaterialTheme.typography.bodyLarge,
                fontWeight = if (!entry.isRead && entry.callType == CallType.MISSED) FontWeight.Bold
                             else FontWeight.Normal,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                color      = if (!entry.isRead && entry.callType == CallType.MISSED) typeColor
                             else MaterialTheme.colorScheme.onSurface,
            )
            if (entry.contactName != null) {
                Text(
                    text  = entry.phoneNumber,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text  = formatRelativeDate(entry.dateMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Right column: SIM badge + duration
        Column(horizontalAlignment = Alignment.End) {
            entry.simLabel?.let { sim ->
                Text(
                    text     = sim,
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .border(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
                Spacer(Modifier.height(2.dp))
            }
            if (entry.durationSec > 0) {
                Text(
                    text  = formatDuration(entry.durationSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// =============================================================================
// Entry action bottom sheet
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryActionSheet(
    entry   : CallLogEntry,
    context : Context,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hasSmsPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.SEND_SMS,
    ) == PackageManager.PERMISSION_GRANTED ||
        // SMS is typically handled by the system SMS app, so we just start the intent.
        true

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        dragHandle       = { BottomSheetDefaults.DragHandle() },
    ) {
        val displayName = entry.contactName?.takeIf { it.isNotBlank() } ?: entry.phoneNumber

        Text(
            text     = displayName,
            style    = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Text(
            text     = entry.phoneNumber,
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(Modifier.height(8.dp))

        // Call Back
        ActionItem(
            icon      = Icons.Filled.Call,
            iconTint  = Color(0xFF4CAF50),
            label     = "Call Back",
            onClick   = {
                onDismiss()
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${entry.phoneNumber}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            },
        )

        // Send SMS
        ActionItem(
            icon      = Icons.AutoMirrored.Filled.Message,
            iconTint  = MaterialTheme.colorScheme.primary,
            label     = "Send Message",
            onClick   = {
                onDismiss()
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${entry.phoneNumber}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try { context.startActivity(intent) }
                catch (_: Exception) {
                    Toast.makeText(context, "No messaging app found", Toast.LENGTH_SHORT).show()
                }
            },
        )

        // Copy Number
        ActionItem(
            icon      = Icons.Filled.ContentCopy,
            iconTint  = MaterialTheme.colorScheme.secondary,
            label     = "Copy Number",
            onClick   = {
                onDismiss()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Phone number", entry.phoneNumber))
                Toast.makeText(context, "Number copied", Toast.LENGTH_SHORT).show()
            },
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ActionItem(
    icon    : ImageVector,
    iconTint: Color,
    label   : String,
    onClick : () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent  = {
            Icon(icon, contentDescription = null, tint = iconTint)
        },
        colors   = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// =============================================================================
// Delete confirm dialog
// =============================================================================

@Composable
private fun DeleteConfirmDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("Delete $count ${if (count == 1) "entry" else "entries"}?") },
        text             = { Text("This will permanently remove the selected call log entries.") },
        confirmButton    = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton    = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// =============================================================================
// Date range picker dialog
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(
    fromMs   : Long?,
    toMs     : Long?,
    onApply  : (Long?, Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var localFrom by remember { mutableStateOf(fromMs) }
    var localTo   by remember { mutableStateOf(toMs) }

    // Use a simple AlertDialog with two date buttons as a lightweight approach.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter by Date") },
        text = {
            Column {
                val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                Text("From: ${localFrom?.let { fmt.format(Date(it)) } ?: "Any"}")
                Row {
                    TextButton(onClick = {
                        // Set start of today as the "from" quick pick
                        localFrom = startOfDay(0)
                    }) { Text("Today") }
                    TextButton(onClick = {
                        localFrom = startOfDay(-6)
                    }) { Text("Last 7 days") }
                    TextButton(onClick = {
                        localFrom = startOfDay(-29)
                    }) { Text("Last 30 days") }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { localFrom = null; localTo = null }) {
                    Text("Clear filter")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(localFrom, localTo) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// =============================================================================
// Empty state
// =============================================================================

@Composable
private fun EmptyState(hasPermission: Boolean, onRequestPermissions: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (!hasPermission) {
                Text("Call log permission required", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRequestPermissions) { Text("Grant permission") }
            } else {
                Text("No calls found", style = MaterialTheme.typography.bodyLarge,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// =============================================================================
// Helper functions
// =============================================================================

private fun callTypeColor(type: CallType): Color = when (type) {
    CallType.INCOMING  -> Color(0xFF4CAF50) // green
    CallType.OUTGOING  -> Color(0xFF2196F3) // blue
    CallType.MISSED    -> Color(0xFFF44336) // red
    CallType.REJECTED  -> Color(0xFFFF5722) // deep orange
    CallType.BLOCKED   -> Color(0xFF9E9E9E) // grey
    CallType.VOICEMAIL -> Color(0xFF9C27B0) // purple
}

private fun callTypeIcon(type: CallType): ImageVector = when (type) {
    CallType.INCOMING  -> Icons.AutoMirrored.Filled.CallReceived
    CallType.OUTGOING  -> Icons.AutoMirrored.Filled.CallMade
    CallType.MISSED    -> Icons.AutoMirrored.Filled.CallMissed
    CallType.REJECTED  -> Icons.Filled.CallEnd
    CallType.BLOCKED   -> Icons.Filled.Block
    CallType.VOICEMAIL -> Icons.AutoMirrored.Filled.Message
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatRelativeDate(dateMs: Long): String {
    val now      = System.currentTimeMillis()
    val cal      = Calendar.getInstance().apply { timeInMillis = dateMs }
    val todayCal = Calendar.getInstance()
    val timeFmt  = SimpleDateFormat("h:mm a", Locale.getDefault())
    val dateFmt  = SimpleDateFormat("MMM d", Locale.getDefault())

    return when {
        isSameDay(cal, todayCal)    -> timeFmt.format(Date(dateMs))
        isYesterday(cal, todayCal)  -> "Yesterday ${timeFmt.format(Date(dateMs))}"
        now - dateMs < 7L * 86400_000 -> {
            SimpleDateFormat("EEE", Locale.getDefault()).format(Date(dateMs)) +
                " ${timeFmt.format(Date(dateMs))}"
        }
        else -> dateFmt.format(Date(dateMs))
    }
}

private fun isSameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
    a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

private fun isYesterday(cal: Calendar, today: Calendar): Boolean {
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return isSameDay(cal, yesterday)
}

private fun startOfDay(offsetDays: Int): Long {
    return Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, offsetDays)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun toggleSelection(id: Long, list: SnapshotStateList<Long>) {
    if (id in list) list.remove(id) else list.add(id)
}

private fun typeFromCode(code: Int): CallType? =
    CallType.entries.firstOrNull { it.code == code }

private fun requiredPermissionsGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
        PackageManager.PERMISSION_GRANTED
