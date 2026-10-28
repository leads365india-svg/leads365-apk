package com.leads365.apk.ui.leads

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeadsScreen(
    viewModel: LeadsViewModel,
    onDirectCall: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val leads by viewModel.leads.collectAsState()
    val context = LocalContext.current

    val handleCallLead: (LeadEntity) -> Unit = { lead ->
        if (onDirectCall != null) {
            onDirectCall(lead.rawNumber)
        } else {
            viewModel.initiateCall(lead, context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "My Leads",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = Slate900
                        )
                        Text(
                            text = if (uiState.lastSyncTimeEpochMs > 0L) {
                                val dateStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                                    .format(Date(uiState.lastSyncTimeEpochMs))
                                "Last synced: $dateStr"
                            } else {
                                "Not yet synced"
                            },
                            fontSize = 11.sp,
                            color = Slate500
                        )
                    }
                },
                actions = {
                    if (uiState.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 8.dp),
                            strokeWidth = 2.dp,
                            color = Blue600
                        )
                    } else {
                        IconButton(onClick = { viewModel.syncLeads() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Leads",
                                tint = Slate600
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = Slate900
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Offline / Sync Status Alert Banner
            if (uiState.isOffline) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Amber50,
                    border = BorderStroke(1.dp, Amber100),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = Amber600,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Offline Mode: Showing cached leads from device storage.",
                            fontSize = 11.sp,
                            color = Amber600,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else if (uiState.syncErrorMessage != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Red50,
                    border = BorderStroke(1.dp, Red100),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Red600,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = uiState.syncErrorMessage ?: "",
                            fontSize = 11.sp,
                            color = Red600,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.clearSyncError() },
                            modifier = Modifier.size(18.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Red600, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search by name, phone or notes...", fontSize = 12.sp, color = Slate400) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Slate400, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Slate400, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Blue600,
                    unfocusedBorderColor = Slate200,
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White
                )
            )

            // Status Filter Chips (Preserve smooth horizontal scrolling, no awkward clipping)
            val statusFilters = listOf("ALL", "New", "Engaged", "Proposal Sent", "Closed-Won", "Closed-Lost")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                statusFilters.forEach { status ->
                    val isSelected = uiState.selectedStatus.equals(status, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.onStatusSelected(status) },
                        shape = RoundedCornerShape(8.dp),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isSelected) Slate900 else Slate200,
                            selectedBorderColor = Slate900
                        ),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Slate900,
                            selectedLabelColor = Color.White,
                            containerColor = Color.White,
                            labelColor = Slate600
                        ),
                        label = {
                            Text(
                                text = status,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    )
                }
            }

            // Lead List or Empty State
            if (leads.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Slate100, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PersonSearch,
                                contentDescription = null,
                                tint = Slate400,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Text(
                            text = if (uiState.searchQuery.isNotBlank() || uiState.selectedStatus != "ALL") {
                                "No leads matching current filters"
                            } else {
                                "No leads assigned yet"
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900
                        )
                        Text(
                            text = "Assigned leads sync automatically from your CRM.",
                            fontSize = 12.sp,
                            color = Slate500,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Button(
                            onClick = { viewModel.syncLeads() },
                            colors = ButtonDefaults.buttonColors(containerColor = Slate900),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(leads, key = { it.id }) { lead ->
                        LeadCard(
                            lead = lead,
                            onCallClick = { handleCallLead(lead) },
                            onCardClick = { viewModel.selectLeadForDetail(lead) }
                        )
                    }
                }
            }
        }
    }

    // Lead Detail Modal Dialog
    uiState.selectedLeadForDetail?.let { lead ->
        AlertDialog(
            onDismissRequest = { viewModel.selectLeadForDetail(null) },
            title = {
                Text(
                    text = lead.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Navy900
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailItem("Phone Number", lead.rawNumber)
                    DetailItem("Normalized Phone", lead.normalizedNumber)
                    DetailItem("Status", lead.status)
                    if (lead.isDoNotRecord) {
                        DetailItem("Recording Policy", "Do Not Record (DNR Enabled)")
                    }
                    if (lead.nextFollowUpEpochMs != null) {
                        val dateStr = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
                            .format(Date(lead.nextFollowUpEpochMs))
                        DetailItem("Next Follow-up", dateStr)
                    }
                    if (lead.notes.isNotBlank()) {
                        DetailItem("Notes", lead.notes)
                    }
                    if (!lead.assignedAgentId.isNullOrBlank()) {
                        DetailItem("Assigned Agent ID", lead.assignedAgentId)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.selectLeadForDetail(null)
                        handleCallLead(lead)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TealSuccess)
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Call Lead")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.selectLeadForDetail(null) }) {
                    Text("Close")
                }
            }
        )
    }

    // Phase 6: Post-Call Outcome Modal Dialog
    uiState.activePostCall?.let { (call, lead) ->
        PostCallOutcomeDialog(
            call = call,
            lead = lead,
            onSaveOutcome = { outcome, notes, followUpMs ->
                viewModel.saveCallOutcome(call.id, outcome, notes, followUpMs)
            },
            onDismiss = {
                viewModel.dismissPostCallDialog()
            }
        )
    }

    // Phase 18: Cloud Telephony In-Flight / Status Dialog
    val cloudCallState by viewModel.cloudCallState.collectAsState()
    when (val state = cloudCallState) {
        is com.leads365.apk.telephony.CloudCallUiState.Initiating -> {
            AlertDialog(
                onDismissRequest = { /* In-flight */ },
                title = { Text("Initiating Cloud Bridge", fontWeight = FontWeight.Bold) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = BlueAccent)
                        Text("Connecting to cloud telephony switch for ${state.leadName}...", fontSize = 13.sp)
                    }
                },
                confirmButton = {}
            )
        }
        is com.leads365.apk.telephony.CloudCallUiState.RingingAgent -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetCloudCallState() },
                title = { Text("Cloud Call Placed", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.message, fontSize = 13.sp, color = Navy900)
                        Text("1. Answer the incoming call on your phone.\n2. You will be automatically bridged to ${state.leadName}.", fontSize = 12.sp, color = TextSecondaryLight)
                    }
                },
                confirmButton = {
                    Button(onClick = { viewModel.resetCloudCallState() }, colors = ButtonDefaults.buttonColors(containerColor = Navy900)) {
                        Text("Got It")
                    }
                }
            )
        }
        is com.leads365.apk.telephony.CloudCallUiState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetCloudCallState() },
                title = { Text("Cloud Call Unavailable", fontWeight = FontWeight.Bold, color = Color(0xFFDC2626)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(state.message, fontSize = 13.sp, color = Navy900)
                        if (state.isProviderUnconfigured) {
                            Text("You can continue calling using your device's native phone dialer.", fontSize = 12.sp, color = TextSecondaryLight)
                        }
                    }
                },
                confirmButton = {
                    if (state.isProviderUnconfigured) {
                        Button(
                            onClick = {
                                viewModel.resetCloudCallState()
                                uiState.selectedLeadForDetail?.let { lead ->
                                    viewModel.initiateCall(lead, context, forceNativeDialer = true)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TealSuccess)
                        ) {
                            Text("Use Native Dialer")
                        }
                    } else {
                        Button(onClick = { viewModel.resetCloudCallState() }) {
                            Text("OK")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.resetCloudCallState() }) {
                        Text("Dismiss")
                    }
                }
            )
        }
        else -> {}
    }
}

@Composable
private fun LeadCard(
    lead: LeadEntity,
    onCallClick: () -> Unit,
    onCardClick: () -> Unit
) {
    Card(
        onClick = onCardClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = lead.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Navy900,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = lead.rawNumber,
                        fontSize = 13.sp,
                        color = TextSecondaryLight
                    )
                }

                // Call Action Button
                IconButton(
                    onClick = onCallClick,
                    modifier = Modifier.size(38.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = TealSuccess.copy(alpha = 0.15f),
                        contentColor = TealSuccess
                    )
                ) {
                    Icon(Icons.Default.Call, contentDescription = "Call Lead", modifier = Modifier.size(20.dp))
                }
            }

            // Badges row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status badge
                StatusBadge(status = lead.status)

                // DNR Badge
                if (lead.isDoNotRecord) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFFEE2E2)
                    ) {
                        Text(
                            text = "DNR",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = RedDanger,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Next follow-up badge
                if (lead.nextFollowUpEpochMs != null) {
                    val dateStr = SimpleDateFormat("MMM d", Locale.getDefault())
                        .format(Date(lead.nextFollowUpEpochMs))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFF1F5F9)
                    ) {
                        Text(
                            text = "Follow-up: $dateStr",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Navy900,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Notes snippet
            if (lead.notes.isNotBlank()) {
                Text(
                    text = lead.notes,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val (bgColor, fgColor) = when (status.lowercase()) {
        "new" -> Pair(Color(0xFFEFF6FF), BlueAccent)
        "engaged" -> Pair(Color(0xFFFEF3C7), Color(0xFFB45309))
        "proposal sent" -> Pair(Color(0xFFF3E8FF), Color(0xFF7E22CE))
        "closed-won" -> Pair(Color(0xFFDCFCE7), Color(0xFF15803D))
        "closed-lost" -> Pair(Color(0xFFF1F5F9), TextSecondaryLight)
        else -> Pair(Color(0xFFF1F5F9), Navy900)
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bgColor
    ) {
        Text(
            text = status,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = fgColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
    Column {
        Text(text = label, fontSize = 11.sp, color = TextSecondaryLight)
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Navy900)
    }
}
