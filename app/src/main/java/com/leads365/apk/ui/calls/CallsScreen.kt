package com.leads365.apk.ui.calls

import android.provider.CallLog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.leads365.apk.core.PrivacyRedactor
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(
    viewModel: CallsViewModel = hiltViewModel(),
    onNavigateToUploads: () -> Unit = {},
    onNavigateToSetup: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val callItems by viewModel.callItems.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val allCount by viewModel.allCount.collectAsState()
    val recordingsCount by viewModel.recordingsCount.collectAsState()
    val leadCount by viewModel.leadCount.collectAsState()
    val unlinkedCount by viewModel.unlinkedCount.collectAsState()
    val isRecordingAccessAvailable by viewModel.isRecordingAccessAvailable.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshDiscoveredRecordings()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Call History & Recordings",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Navy900
                        )
                        Text(
                            text = "$allCount calls captured • $recordingsCount recordings",
                            fontSize = 11.sp,
                            color = TextSecondaryLight
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToUploads,
                        modifier = Modifier.size(48.dp)
                    ) {
                        BadgedBox(
                            badge = {
                                if (recordingsCount > 0) {
                                    Badge(
                                        containerColor = TealSuccess,
                                        contentColor = Color.White
                                    ) {
                                        Text("$recordingsCount")
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = "Upload Pipeline",
                                tint = BlueAccent
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
        ) {
            // 1. Compact warning card only when recording folder / MediaStore is not accessible
            if (!isRecordingAccessAvailable) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clickable { onNavigateToSetup() },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = AmberWarning.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.40f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = AmberWarning,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Recording folder inactive",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Navy900
                            )
                        }
                        Text("Setup →", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BlueAccent)
                    }
                }
            }

            // 2. Filter Tabs Row (touch target >= 48dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == CallFilterTab.ALL,
                    onClick = { viewModel.setFilter(CallFilterTab.ALL) },
                    label = { Text("All ($allCount)", fontSize = 12.sp) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BlueAccent.copy(alpha = 0.12f),
                        selectedLabelColor = BlueAccent
                    )
                )

                FilterChip(
                    selected = selectedFilter == CallFilterTab.WITH_RECORDINGS,
                    onClick = { viewModel.setFilter(CallFilterTab.WITH_RECORDINGS) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Recordings ($recordingsCount)", fontSize = 12.sp)
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TealSuccess.copy(alpha = 0.15f),
                        selectedLabelColor = TealSuccess
                    )
                )

                FilterChip(
                    selected = selectedFilter == CallFilterTab.LEAD_CALLS,
                    onClick = { viewModel.setFilter(CallFilterTab.LEAD_CALLS) },
                    label = { Text("Leads ($leadCount)", fontSize = 12.sp) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BlueAccent.copy(alpha = 0.12f),
                        selectedLabelColor = BlueAccent
                    )
                )

                FilterChip(
                    selected = selectedFilter == CallFilterTab.UNLINKED,
                    onClick = { viewModel.setFilter(CallFilterTab.UNLINKED) },
                    label = { Text("Unlinked Inbound ($unlinkedCount)", fontSize = 12.sp) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TealSuccess.copy(alpha = 0.12f),
                        selectedLabelColor = TealSuccess
                    )
                )
            }

            // 3. Call List or Helpful Empty State
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                if (callItems.isEmpty()) {
                    EmptyCallState(
                        filter = selectedFilter,
                        recordingsCount = recordingsCount,
                        onViewRecordings = { viewModel.setFilter(CallFilterTab.WITH_RECORDINGS) },
                        onNavigateToSetup = onNavigateToSetup
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(callItems, key = { it.id }) { item ->
                            if (item.isCandidateOnly) {
                                DiscoveredRecordingCandidateCard(
                                    item = item,
                                    onPlay = { viewModel.playRecording(item.recordingUri) }
                                )
                            } else if (item.call != null) {
                                CallRecordCard(
                                    item = item,
                                    onPlay = { viewModel.playRecording(item.recordingUri) },
                                    onRetryUpload = { item.uploadTask?.let { viewModel.retryUpload(it) } }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiscoveredRecordingCandidateCard(
    item: CallRecordUiModel,
    onPlay: () -> Unit
) {
    val candidate = item.candidate ?: return
    val dateStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        .format(Date(candidate.lastModifiedEpochMs))
    val sizeStr = formatBytes(candidate.sizeBytes)
    val metaText = if (sizeStr.isNotBlank()) "$dateStr • $sizeStr" else dateStr

    val isReadable = candidate.isReadable
    val statusColor = if (!isReadable) RedDanger else BlueAccent
    val statusLabel = if (!isReadable) "Inaccessible" else "Unmatched"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = candidate.displayName,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = Navy900,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = metaText,
                            fontSize = 11.sp,
                            color = TextSecondaryLight
                        )
                        Surface(
                            color = statusColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = statusLabel,
                                color = statusColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                            )
                        }
                    }
                }
            }

            if (isReadable) {
                OutlinedButton(
                    onClick = onPlay,
                    modifier = Modifier.heightIn(min = 36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, BlueAccent.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Audio",
                        tint = BlueAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play", fontSize = 11.sp, color = BlueAccent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun CallRecordCard(
    item: CallRecordUiModel,
    onPlay: () -> Unit,
    onRetryUpload: () -> Unit
) {
    val call = item.call ?: return
    val (directionIcon, directionColor, directionLabel) = when (call.callType) {
        CallLog.Calls.INCOMING_TYPE -> Triple(Icons.AutoMirrored.Filled.CallReceived, TealSuccess, "Incoming")
        CallLog.Calls.OUTGOING_TYPE -> Triple(Icons.AutoMirrored.Filled.CallMade, BlueAccent, "Outgoing")
        CallLog.Calls.MISSED_TYPE -> Triple(Icons.AutoMirrored.Filled.CallMissed, RedDanger, "Missed")
        CallLog.Calls.REJECTED_TYPE -> Triple(Icons.AutoMirrored.Filled.CallMissed, RedDanger, "Rejected")
        else -> Triple(Icons.Default.Call, Navy900, "Call")
    }

    val durationMin = call.durationSeconds / 60
    val durationSec = call.durationSeconds % 60
    val durationStr = if (durationMin > 0) "${durationMin}m ${durationSec}s" else "${durationSec}s"

    val dateStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(call.startTimeEpochMs))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header Row: Icon, Number, Unlinked badge, Duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(directionColor.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = directionIcon,
                            contentDescription = directionLabel,
                            tint = directionColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = PrivacyRedactor.redactPhoneNumber(call.normalizedNumber),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Navy900
                            )
                            if (item.isUnlinkedInbound) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = TealSuccess.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "Unlinked Customer",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TealSuccess,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = "$directionLabel • $dateStr",
                            fontSize = 11.sp,
                            color = TextSecondaryLight
                        )
                    }
                }

                // Duration or Missed
                if (call.callType == CallLog.Calls.MISSED_TYPE || call.callType == CallLog.Calls.REJECTED_TYPE) {
                    Text(
                        text = "No Answer",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = RedDanger
                    )
                } else {
                    Text(
                        text = durationStr,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Navy900
                    )
                }
            }

            // Outcome & CRM Report Status Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!call.callOutcome.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = BlueAccent.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "Outcome: ${call.callOutcome}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = BlueAccent,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFF1F5F9)
                    ) {
                        Text(
                            text = "Pending Outcome",
                            fontSize = 10.sp,
                            color = TextSecondaryLight,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Call metadata sync status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    when (call.syncStatus) {
                        "SYNCED" -> {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = TealSuccess, modifier = Modifier.size(12.dp))
                            Text("Call Reported", fontSize = 10.sp, color = TealSuccess, fontWeight = FontWeight.SemiBold)
                        }
                        "FAILED" -> {
                            Icon(Icons.Default.SyncProblem, contentDescription = null, tint = RedDanger, modifier = Modifier.size(12.dp))
                            Text("Report Failed", fontSize = 10.sp, color = RedDanger, fontWeight = FontWeight.SemiBold)
                        }
                        else -> {
                            Icon(Icons.Default.Schedule, contentDescription = null, tint = TextSecondaryLight, modifier = Modifier.size(12.dp))
                            Text("Local Queued", fontSize = 10.sp, color = TextSecondaryLight)
                        }
                    }
                }
            }

            // Notes if present
            if (!call.notes.isNullOrBlank()) {
                Text(
                    text = "Note: ${call.notes}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 4. DEDICATED RECORDING VISIBILITY CARD
            if (item.hasRecording) {
                RecordingSectionCard(
                    item = item,
                    onPlay = onPlay,
                    onRetryUpload = onRetryUpload
                )
            }
        }
    }
}

@Composable
fun RecordingSectionCard(
    item: CallRecordUiModel,
    onPlay: () -> Unit,
    onRetryUpload: () -> Unit
) {
    val uploadTask = item.uploadTask
    val fileName = item.recordingFileName ?: "Call Recording"
    val fileSizeFormatted = formatBytes(item.recordingFileSizeBytes ?: 0L)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Audio Recording",
                        tint = TealSuccess,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = fileName,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = Navy900,
                            maxLines = 1
                        )
                        if (fileSizeFormatted.isNotBlank()) {
                            Text(
                                text = fileSizeFormatted,
                                fontSize = 10.sp,
                                color = TextSecondaryLight
                            )
                        }
                    }
                }

                // Upload Pipeline Status Badge
                UploadPipelineBadge(uploadTask = uploadTask, hasRecording = item.hasRecording)
            }

            // Play & Retry Action Row (Touch target >= 48dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!item.recordingUri.isNullOrBlank()) {
                    OutlinedButton(
                        onClick = onPlay,
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, BlueAccent.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play Audio",
                            tint = BlueAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play", fontSize = 11.sp, color = BlueAccent, fontWeight = FontWeight.Bold)
                    }
                }

                if (uploadTask?.status == UploadTaskEntity.STATE_RETRY_WAIT ||
                    uploadTask?.status == UploadTaskEntity.STATUS_FAILED ||
                    uploadTask?.status == UploadTaskEntity.STATE_FAILED_PERMANENT
                ) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onRetryUpload,
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry Upload",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry Upload", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun UploadPipelineBadge(uploadTask: UploadTaskEntity?, hasRecording: Boolean) {
    when {
        uploadTask?.status == UploadTaskEntity.STATE_UPLOADED || uploadTask?.status == UploadTaskEntity.STATUS_COMPLETED -> {
            Surface(shape = RoundedCornerShape(4.dp), color = TealSuccess.copy(alpha = 0.15f)) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = TealSuccess, modifier = Modifier.size(12.dp))
                    Text("Uploaded to CRM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TealSuccess)
                }
            }
        }
        uploadTask?.status == UploadTaskEntity.STATE_UPLOADING || uploadTask?.status == UploadTaskEntity.STATUS_IN_PROGRESS -> {
            Surface(shape = RoundedCornerShape(4.dp), color = BlueAccent.copy(alpha = 0.15f)) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = BlueAccent)
                    Text("Uploading...", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = BlueAccent)
                }
            }
        }
        uploadTask?.status == UploadTaskEntity.STATE_RETRY_WAIT -> {
            Surface(shape = RoundedCornerShape(4.dp), color = AmberWarning.copy(alpha = 0.15f)) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = AmberWarning, modifier = Modifier.size(12.dp))
                    Text("Retry Waiting", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AmberWarning)
                }
            }
        }
        uploadTask?.status == UploadTaskEntity.STATUS_FAILED || uploadTask?.status == UploadTaskEntity.STATE_FAILED_PERMANENT -> {
            Surface(shape = RoundedCornerShape(4.dp), color = RedDanger.copy(alpha = 0.15f)) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.SyncProblem, contentDescription = null, tint = RedDanger, modifier = Modifier.size(12.dp))
                    Text("Upload Failed", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = RedDanger)
                }
            }
        }
        uploadTask?.status == UploadTaskEntity.STATUS_PENDING || uploadTask?.status == UploadTaskEntity.STATE_AWAITING_FILE -> {
            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFE2E8F0)) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = TextSecondaryLight, modifier = Modifier.size(12.dp))
                    Text("Upload Queued", fontSize = 10.sp, color = TextSecondaryLight)
                }
            }
        }
        hasRecording -> {
            Surface(shape = RoundedCornerShape(4.dp), color = BlueAccent.copy(alpha = 0.12f)) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = BlueAccent, modifier = Modifier.size(12.dp))
                    Text("Recording Found", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = BlueAccent)
                }
            }
        }
    }
}

@Composable
fun EmptyCallState(
    filter: CallFilterTab,
    recordingsCount: Int = 0,
    onViewRecordings: () -> Unit = {},
    onNavigateToSetup: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val (icon, title, desc, showSetupBtn) = when (filter) {
            CallFilterTab.WITH_RECORDINGS -> Quadruple(
                Icons.Default.Mic,
                "No Audio Recordings Detected",
                "Ensure call recording is enabled in your phone dialer settings (Vivo, Samsung, Xiaomi) and your recording folder is selected in Device Setup.",
                true
            )
            CallFilterTab.UNLINKED -> Quadruple(
                Icons.Default.PhoneInTalk,
                "No Unlinked Incoming Calls",
                "Incoming calls from customer numbers not yet in your CRM will appear here automatically with their recordings preserved.",
                false
            )
            CallFilterTab.LEAD_CALLS -> Quadruple(
                Icons.Default.PhoneInTalk,
                "No Lead Calls Recorded",
                "Completed calls made to or received from your assigned leads will appear here with automatic recording discovery.",
                true
            )
            CallFilterTab.ALL -> if (recordingsCount > 0) {
                Quadruple(
                    Icons.Default.FolderOpen,
                    "$recordingsCount Audio Recordings Detected",
                    "Leads365 discovered $recordingsCount recording files in your Record/Call folder. Once Phone State and Call Log permissions are active, calls will match automatically.",
                    false
                )
            } else {
                Quadruple(
                    Icons.Default.PhoneInTalk,
                    "No Call Activity Yet",
                    "Completed phone calls with your leads or incoming customers will appear here automatically with outcome tagging.",
                    true
                )
            }
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (recordingsCount > 0 && filter == CallFilterTab.ALL) BlueAccent else TextSecondaryLight,
            modifier = Modifier.size(54.dp)
        )
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = Navy900
        )
        Text(
            text = desc,
            fontSize = 12.sp,
            color = TextSecondaryLight,
            modifier = Modifier.padding(horizontal = 32.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        if (filter == CallFilterTab.ALL && recordingsCount > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = onViewRecordings,
                colors = ButtonDefaults.buttonColors(containerColor = Navy800),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.heightIn(min = 44.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("View Discovered Recordings ($recordingsCount)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (showSetupBtn) {
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = onNavigateToSetup,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, BlueAccent)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = BlueAccent, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Configure Recording Folder", color = BlueAccent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    return when {
        bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
