package com.leads365.apk.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.leads365.apk.core.PrivacyRedactor
import com.leads365.apk.data.repository.AuthState
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToLeads: () -> Unit = {},
    onNavigateToCalls: () -> Unit = {},
    onNavigateToUploads: () -> Unit = {},
    onNavigateToSetup: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAccount: () -> Unit = {},
    onNavigateToPrivacy: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val authState by viewModel.authState.collectAsState()
    val calls by viewModel.calls.collectAsState()
    val leadsCount by viewModel.leadsCount.collectAsState()
    val uploadTasks by viewModel.uploadTasks.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val isRecordingAccessAvailable by viewModel.isRecordingAccessAvailable.collectAsState()
    val discoveredRecordingsCount by viewModel.discoveredRecordingsCount.collectAsState()

    val callsWithRecordingsCount = calls.count { it.hasRecording }
    val totalCallsCount = calls.size
    val pendingUploadsCount = uploadTasks.count {
        it.status == UploadTaskEntity.STATUS_PENDING ||
                it.status == UploadTaskEntity.STATE_UPLOADING ||
                it.status == UploadTaskEntity.STATE_RETRY_WAIT ||
                it.status == UploadTaskEntity.STATE_AWAITING_FILE ||
                it.status == UploadTaskEntity.STATE_FILE_FOUND
    }
    val completedUploadsCount = uploadTasks.count {
        it.status == UploadTaskEntity.STATE_UPLOADED ||
                it.status == UploadTaskEntity.STATUS_COMPLETED
    }

    val agentName = when (val state = authState) {
        is AuthState.Authenticated -> state.user.name
        else -> "Agent (Local Test Mode)"
    }
    val agencyName = when (val state = authState) {
        is AuthState.Authenticated -> state.agency?.name ?: "Leads365 Direct"
        else -> "Local Device Testing"
    }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = BlueAccent.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(
                                    text = "LEADS365",
                                    color = BlueAccent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = "Home Dashboard",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = Navy900
                            )
                        }
                        Text(
                            text = "$agentName • $agencyName",
                            fontSize = 11.sp,
                            color = TextSecondaryLight
                        )
                    }
                },
                actions = {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 8.dp),
                            strokeWidth = 2.dp,
                            color = BlueAccent
                        )
                    } else {
                        IconButton(
                            onClick = { viewModel.syncNow() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sync",
                                tint = Navy900
                            )
                        }
                    }

                    IconButton(
                        onClick = onNavigateToAccount,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Account",
                            tint = Navy900
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Hardware Recording Readiness Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRecordingAccessAvailable) TealSuccess.copy(alpha = 0.08f) else AmberWarning.copy(alpha = 0.10f)
                ),
                border = BorderStroke(
                    1.dp,
                    if (isRecordingAccessAvailable) TealSuccess.copy(alpha = 0.35f) else AmberWarning.copy(alpha = 0.40f)
                )
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isRecordingAccessAvailable) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isRecordingAccessAvailable) TealSuccess else AmberWarning,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isRecordingAccessAvailable) "Recording Folder Active" else "Recording Folder Setup Required",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Navy900
                            )
                        }

                        Surface(
                            color = if (isRecordingAccessAvailable) TealSuccess.copy(alpha = 0.18f) else AmberWarning.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (isRecordingAccessAvailable) "DISCOVERY READY" else "ACTION NEEDED",
                                color = if (isRecordingAccessAvailable) TealSuccess else Color(0xFFD97706),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = if (isRecordingAccessAvailable) {
                            "Hardware dialer call recordings will be automatically discovered, matched to assigned leads, and queued for upload."
                        } else {
                            "Grant access to your device dialer's recording folder (e.g. Recordings/Call or Record/Call) to enable automated call discovery."
                        },
                        fontSize = 11.sp,
                        color = TextSecondaryLight,
                        lineHeight = 16.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = onNavigateToSetup,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(
                                text = if (isRecordingAccessAvailable) "Review Folder Access →" else "Configure Folder Now →",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = BlueAccent
                            )
                        }
                    }
                }
            }

            // 2. Operational Metrics Overview (4 Stat Cards)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "OPERATIONAL OVERVIEW",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondaryLight,
                    letterSpacing = 0.5.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricCard(
                        title = "Assigned Leads",
                        value = "$leadsCount",
                        subtext = "Active CRM Leads",
                        icon = Icons.AutoMirrored.Filled.List,
                        accentColor = BlueAccent,
                        onClick = onNavigateToLeads,
                        modifier = Modifier.weight(1f)
                    )

                    MetricCard(
                        title = "Calls Captured",
                        value = "$totalCallsCount",
                        subtext = "Telephony Events",
                        icon = Icons.Default.Call,
                        accentColor = Navy900,
                        onClick = onNavigateToCalls,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val displayRecCount = if (callsWithRecordingsCount > 0) callsWithRecordingsCount else discoveredRecordingsCount
                    val recSubtext = if (callsWithRecordingsCount > 0) {
                        "Audio Files Matched"
                    } else if (discoveredRecordingsCount > 0) {
                        "$discoveredRecordingsCount in folder (0 matched)"
                    } else {
                        "No Audio Detected"
                    }

                    MetricCard(
                        title = "Recordings Found",
                        value = "$displayRecCount",
                        subtext = recSubtext,
                        icon = Icons.Default.Mic,
                        accentColor = TealSuccess,
                        onClick = onNavigateToCalls,
                        modifier = Modifier.weight(1f)
                    )

                    MetricCard(
                        title = "Upload Pipeline",
                        value = "$completedUploadsCount",
                        subtext = if (pendingUploadsCount > 0) "$pendingUploadsCount pending" else "All sync'd",
                        icon = Icons.Default.CloudUpload,
                        accentColor = if (pendingUploadsCount > 0) Color(0xFFD97706) else TealSuccess,
                        onClick = onNavigateToUploads,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 3. Quick Navigation Hub (touch target >= 48dp)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "WORKFLOW HUB",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondaryLight,
                    letterSpacing = 0.5.sp
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = LightSurface),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        QuickActionRow(
                            icon = Icons.Default.Call,
                            title = "Calls & Recording Discovery",
                            subtitle = "View real calls, audio files, playback & upload statuses",
                            onClick = onNavigateToCalls
                        )
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                        QuickActionRow(
                            icon = Icons.AutoMirrored.Filled.List,
                            title = "My Leads Directory",
                            subtitle = "Assigned contacts, direct calling & post-call outcome notes",
                            onClick = onNavigateToLeads
                        )
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                        QuickActionRow(
                            icon = Icons.Default.CloudUpload,
                            title = "Recording Upload Pipeline",
                            subtitle = "Inspect background WorkManager queue, hashes & retry status",
                            onClick = onNavigateToUploads
                        )
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                        QuickActionRow(
                            icon = Icons.Default.Build,
                            title = "Device Setup & Recording Wizard",
                            subtitle = "Step 1–4 permissions, SAF folder picker & hardware call simulation",
                            onClick = onNavigateToSetup
                        )
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                        QuickActionRow(
                            icon = Icons.Default.Settings,
                            title = "Settings, Diagnostics & Self-Test",
                            subtitle = "Readiness audit, battery hardening & caller overlay controls",
                            onClick = onNavigateToSettings
                        )
                    }
                }
            }

            // 4. Recent Calls / Discovered Audio Feed
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RECENT CAPTURED CALLS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondaryLight,
                        letterSpacing = 0.5.sp
                    )

                    if (calls.isNotEmpty()) {
                        TextButton(
                            onClick = onNavigateToCalls,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("View All (${calls.size}) →", fontSize = 11.sp, color = BlueAccent, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (calls.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = LightSurface),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneCallback,
                                contentDescription = null,
                                tint = TextTertiaryLight,
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = "No Calls Captured Yet",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Navy900
                            )
                            Text(
                                text = "When you place or receive phone calls with assigned leads or customer contacts, native dialer calls and audio recordings appear here.",
                                fontSize = 11.sp,
                                color = TextSecondaryLight,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                lineHeight = 16.sp
                            )
                            Button(
                                onClick = onNavigateToSetup,
                                colors = ButtonDefaults.buttonColors(containerColor = Navy900),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("Run Test Call in Setup", fontSize = 11.sp)
                            }
                        }
                    }
                } else {
                    val recentThree = calls.take(3)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        recentThree.forEach { call ->
                            RecentCallSnippetCard(call = call, onOpenCalls = onNavigateToCalls)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    subtext: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    color = TextSecondaryLight,
                    fontWeight = FontWeight.Medium
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(accentColor.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Text(
                text = value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Navy900
            )

            Text(
                text = subtext,
                fontSize = 10.sp,
                color = TextTertiaryLight
            )
        }
    }
}

@Composable
private fun QuickActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(BlueAccent.copy(alpha = 0.10f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = BlueAccent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Navy900
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TextSecondaryLight
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = TextTertiaryLight,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun RecentCallSnippetCard(
    call: CallEntity,
    onOpenCalls: () -> Unit
) {
    val (directionIcon, directionColor) = when (call.callType) {
        android.provider.CallLog.Calls.OUTGOING_TYPE -> Pair(Icons.AutoMirrored.Filled.CallMade, BlueAccent)
        android.provider.CallLog.Calls.INCOMING_TYPE -> Pair(Icons.AutoMirrored.Filled.CallReceived, TealSuccess)
        else -> Pair(Icons.AutoMirrored.Filled.CallMissed, RedDanger)
    }

    val dateStr = remember(call.startTimeEpochMs) {
        if (call.startTimeEpochMs > 0L) {
            SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(call.startTimeEpochMs))
        } else {
            "Recent"
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenCalls)
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(directionColor.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = directionIcon,
                        contentDescription = null,
                        tint = directionColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = PrivacyRedactor.redactPhoneNumber(call.rawNumber),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Navy900
                    )
                    Text(
                        text = "$dateStr • ${call.durationSeconds}s",
                        fontSize = 11.sp,
                        color = TextSecondaryLight
                    )
                }
            }

            if (call.hasRecording) {
                Surface(
                    color = TealSuccess.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = TealSuccess,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "AUDIO MATCHED",
                            color = TealSuccess,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
