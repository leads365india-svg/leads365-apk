package com.leads365.apk.ui.leads

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
                            fontSize = 18.sp,
                            color = Navy900
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
                            color = TextSecondaryLight
                        )
                    }
                },
                actions = {
                    if (uiState.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 8.dp),
                            strokeWidth = 2.dp,
                            color = BlueAccent
                        )
                    } else {
                        IconButton(onClick = { viewModel.syncLeads() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Leads",
                                tint = Navy900
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
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
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFEF3C7),
                    border = BorderStroke(1.dp, Color(0xFFFDE68A)),
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
                            tint = Color(0xFFB45309),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Offline Mode: Showing cached leads from device storage.",
                            fontSize = 11.sp,
                            color = Color(0xFF92400E),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else if (uiState.syncErrorMessage != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFEE2E2),
                    border = BorderStroke(1.dp, Color(0xFFFECACA)),
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
                            tint = RedDanger,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = uiState.syncErrorMessage ?: "",
                            fontSize = 11.sp,
                            color = RedDanger,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.clearSyncError() },
                            modifier = Modifier.size(18.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = RedDanger, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search by name, phone or notes...", fontSize = 13.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondaryLight)
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextSecondaryLight)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BlueAccent,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            // Status Filter Chips
            val statusFilters = listOf("ALL", "New", "Engaged", "Proposal Sent", "Closed-Won", "Closed-Lost")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                statusFilters.forEach { status ->
                    val isSelected = uiState.selectedStatus.equals(status, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.onStatusSelected(status) },
                        label = {
                            Text(
                                text = status,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Navy900,
                            selectedLabelColor = Color.White
                        )
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
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonSearch,
                            contentDescription = null,
                            tint = TextSecondaryLight,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = if (uiState.searchQuery.isNotBlank() || uiState.selectedStatus != "ALL") {
                                "No leads matching current filters"
                            } else {
                                "No leads assigned yet"
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Navy900
                        )
                        Text(
                            text = "Assigned leads sync automatically from your CRM.",
                            fontSize = 12.sp,
                            color = TextSecondaryLight
                        )
                        Button(
                            onClick = { viewModel.syncLeads() },
                            colors = ButtonDefaults.buttonColors(containerColor = Navy900),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync Now")
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
