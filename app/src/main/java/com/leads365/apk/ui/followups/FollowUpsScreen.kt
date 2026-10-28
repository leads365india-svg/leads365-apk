package com.leads365.apk.ui.followups

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.leads365.apk.database.entity.FollowUpEntity
import com.leads365.apk.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowUpsScreen(
    viewModel: FollowUpsViewModel = hiltViewModel(),
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val selectedTab by viewModel.selectedTab.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val (todayList, upcomingList, overdueList) = viewModel.followUpsState.collectAsState().value

    var showNewFollowUpDialog by remember { mutableStateOf(false) }
    var completeTarget by remember { mutableStateOf<FollowUpEntity?>(null) }
    var rescheduleTarget by remember { mutableStateOf<FollowUpEntity?>(null) }

    val currentList = when (selectedTab) {
        FollowUpTab.TODAY -> todayList
        FollowUpTab.UPCOMING -> upcomingList
        FollowUpTab.OVERDUE -> overdueList
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Follow-ups & Reminders",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Navy900
                        )
                        Text(
                            text = "${todayList.size} today • ${overdueList.size} overdue",
                            fontSize = 11.sp,
                            color = TextSecondaryLight
                        )
                    }
                },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Navy900)
                        }
                    }
                },
                actions = {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp,
                            color = BlueAccent
                        )
                    } else {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Navy900)
                        }
                    }
                    IconButton(onClick = { showNewFollowUpDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Follow-up", tint = BlueAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFF8FAFC))
        ) {
            // Error banner if any
            if (!errorMessage.isNullOrBlank()) {
                Surface(
                    color = Color(0xFFFEE2E2),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = errorMessage ?: "", color = Color(0xFF991B1B), fontSize = 11.sp)
                    }
                }
            }

            // Tab Row
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = Color.White,
                contentColor = BlueAccent
            ) {
                Tab(
                    selected = selectedTab == FollowUpTab.TODAY,
                    onClick = { viewModel.selectTab(FollowUpTab.TODAY) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Today", fontWeight = if (selectedTab == FollowUpTab.TODAY) FontWeight.Bold else FontWeight.Normal)
                            if (todayList.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                TabBadge(count = todayList.size, color = BlueAccent)
                            }
                        }
                    }
                )
                Tab(
                    selected = selectedTab == FollowUpTab.UPCOMING,
                    onClick = { viewModel.selectTab(FollowUpTab.UPCOMING) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Upcoming", fontWeight = if (selectedTab == FollowUpTab.UPCOMING) FontWeight.Bold else FontWeight.Normal)
                            if (upcomingList.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                TabBadge(count = upcomingList.size, color = Color(0xFF64748B))
                            }
                        }
                    }
                )
                Tab(
                    selected = selectedTab == FollowUpTab.OVERDUE,
                    onClick = { viewModel.selectTab(FollowUpTab.OVERDUE) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Overdue", fontWeight = if (selectedTab == FollowUpTab.OVERDUE) FontWeight.Bold else FontWeight.Normal)
                            if (overdueList.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                TabBadge(count = overdueList.size, color = Color(0xFFEF4444))
                            }
                        }
                    }
                )
            }

            // Content List
            if (currentList.isEmpty()) {
                EmptyFollowUpView(selectedTab = selectedTab, onScheduleClick = { showNewFollowUpDialog = true })
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(currentList, key = { it.id }) { item ->
                        FollowUpCard(
                            item = item,
                            onCallClick = { phone ->
                                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(dialIntent)
                            },
                            onCompleteClick = { completeTarget = item },
                            onRescheduleClick = { rescheduleTarget = item }
                        )
                    }
                }
            }
        }
    }

    // Complete Dialog
    completeTarget?.let { target ->
        var notes by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { completeTarget = null },
            title = { Text("Complete Follow-up", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Lead: ${target.leadName.ifBlank { "Unassigned Lead" }}", fontSize = 13.sp, color = TextSecondaryLight)
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Outcome / Call Notes") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.completeFollowUp(target.id, notes)
                        completeTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TealSuccess)
                ) {
                    Text("Mark Completed")
                }
            },
            dismissButton = {
                TextButton(onClick = { completeTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Reschedule Dialog
    rescheduleTarget?.let { target ->
        var selectedDelayHours by remember { mutableStateOf(1) }
        var notes by remember { mutableStateOf(target.notes) }

        AlertDialog(
            onDismissRequest = { rescheduleTarget = null },
            title = { Text("Reschedule Follow-up", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Target: ${target.leadName} (${target.title})", fontSize = 12.sp, color = TextSecondaryLight)
                    Text("Choose new time:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = selectedDelayHours == 1,
                            onClick = { selectedDelayHours = 1 },
                            label = { Text("+1 Hour", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = selectedDelayHours == 4,
                            onClick = { selectedDelayHours = 4 },
                            label = { Text("+4 Hours", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = selectedDelayHours == 24,
                            onClick = { selectedDelayHours = 24 },
                            label = { Text("Tomorrow", fontSize = 11.sp) }
                        )
                    }

                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Updated Notes") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newTime = System.currentTimeMillis() + (selectedDelayHours * 3600_000L)
                        viewModel.rescheduleFollowUp(target.id, newTime, notes)
                        rescheduleTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
                ) {
                    Text("Save New Time")
                }
            },
            dismissButton = {
                TextButton(onClick = { rescheduleTarget = null }) { Text("Cancel") }
            }
        )
    }

    // New Follow-up Dialog
    if (showNewFollowUpDialog) {
        var leadName by remember { mutableStateOf("") }
        var leadPhone by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("Follow-up Call") }
        var notes by remember { mutableStateOf("") }
        var delayHours by remember { mutableStateOf(2) }

        AlertDialog(
            onDismissRequest = { showNewFollowUpDialog = false },
            title = { Text("New Follow-up", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = leadName,
                        onValueChange = { leadName = it },
                        label = { Text("Customer / Lead Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = leadPhone,
                        onValueChange = { leadPhone = it },
                        label = { Text("Phone Number") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = delayHours == 1,
                            onClick = { delayHours = 1 },
                            label = { Text("in 1h", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = delayHours == 4,
                            onClick = { delayHours = 4 },
                            label = { Text("in 4h", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = delayHours == 24,
                            onClick = { delayHours = 24 },
                            label = { Text("Tomorrow", fontSize = 11.sp) }
                        )
                    }
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val epochMs = System.currentTimeMillis() + (delayHours * 3600_000L)
                        viewModel.createFollowUp(
                            leadId = null,
                            leadName = leadName,
                            leadPhone = leadPhone,
                            title = title,
                            scheduledAtEpochMs = epochMs,
                            notes = notes
                        )
                        showNewFollowUpDialog = false
                    },
                    enabled = leadName.isNotBlank() || leadPhone.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Navy900)
                ) {
                    Text("Schedule")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFollowUpDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun FollowUpCard(
    item: FollowUpEntity,
    onCallClick: (String) -> Unit,
    onCompleteClick: () -> Unit,
    onRescheduleClick: () -> Unit
) {
    val timeFormat = SimpleDateFormat("h:mm a, d MMM", Locale.getDefault())
    val formattedTime = timeFormat.format(Date(item.scheduledAtEpochMs))

    val isOverdue = item.scheduledAtEpochMs < System.currentTimeMillis() && item.status == FollowUpEntity.STATUS_PENDING

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, if (isOverdue) Color(0xFFFCA5A5) else Color(0xFFE2E8F0))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header Row: Lead Name & Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.leadName.ifBlank { item.title },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Navy900
                    )
                    if (item.leadPhone.isNotBlank()) {
                        Text(
                            text = item.leadPhone,
                            fontSize = 12.sp,
                            color = TextSecondaryLight
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isOverdue) {
                        Surface(
                            color = Color(0xFFFEE2E2),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "OVERDUE",
                                color = Color(0xFFDC2626),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (item.syncStatus != FollowUpEntity.SYNC_STATUS_SYNCED) {
                        Icon(
                            Icons.Default.CloudQueue,
                            contentDescription = "Pending Sync",
                            tint = Color(0xFFD97706),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Title & Notes
            if (item.title.isNotBlank() && item.title != item.leadName) {
                Text(text = item.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Navy900)
            }
            if (item.notes.isNotBlank()) {
                Text(text = item.notes, fontSize = 12.sp, color = TextSecondaryLight)
            }

            // Scheduled Time Display
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AccessTime,
                    contentDescription = null,
                    tint = if (isOverdue) Color(0xFFDC2626) else BlueAccent,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = formattedTime,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isOverdue) Color(0xFFDC2626) else BlueAccent
                )
            }

            HorizontalDivider(color = Color(0xFFF1F5F9))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.leadPhone.isNotBlank()) {
                    IconButton(
                        onClick = { onCallClick(item.leadPhone) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = "Call", tint = TealSuccess)
                    }
                }

                OutlinedButton(
                    onClick = onRescheduleClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 32.dp)
                ) {
                    Text("Reschedule", fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.width(6.dp))

                Button(
                    onClick = onCompleteClick,
                    colors = ButtonDefaults.buttonColors(containerColor = TealSuccess),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 32.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Done", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun EmptyFollowUpView(
    selectedTab: FollowUpTab,
    onScheduleClick: () -> Unit
) {
    val (title, subtitle) = when (selectedTab) {
        FollowUpTab.TODAY -> Pair("No Follow-ups Due Today", "All scheduled calls for today are clear.")
        FollowUpTab.UPCOMING -> Pair("No Upcoming Follow-ups", "Schedule future calls to nurture your leads.")
        FollowUpTab.OVERDUE -> Pair("Zero Overdue Follow-ups", "Great job! All customer reminders are on track.")
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            color = BlueAccent.copy(alpha = 0.1f),
            shape = CircleShape,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (selectedTab == FollowUpTab.OVERDUE) Icons.Default.CheckCircle else Icons.Default.EventAvailable,
                    contentDescription = null,
                    tint = if (selectedTab == FollowUpTab.OVERDUE) TealSuccess else BlueAccent,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Navy900)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = subtitle, fontSize = 12.sp, color = TextSecondaryLight, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onScheduleClick,
            colors = ButtonDefaults.buttonColors(containerColor = Navy900)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Schedule Follow-up", fontSize = 12.sp)
        }
    }
}

@Composable
private fun TabBadge(count: Int, color: Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = CircleShape
    ) {
        Text(
            text = count.toString(),
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
