package com.leads365.apk.ui.privacy

import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(
    viewModel: PrivacyViewModel = hiltViewModel()
) {
    val policy by viewModel.policy.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val consentAcknowledged by viewModel.consentAcknowledged.collectAsState()
    val uploadHistory by viewModel.uploadHistory.collectAsState()

    // Show consent dialog when required
    if (uiState.showConsentDialog) {
        ConsentNoticeDialog(
            consentText = policy.consentNoticeText,
            onAcknowledge = { viewModel.acknowledgeConsent() },
            onDismiss = { viewModel.dismissConsentDialog() }
        )
    }

    // Snackbar host state
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearSuccessMessage()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar("Error: $it", duration = SnackbarDuration.Short)
            viewModel.clearErrorMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Privacy & Recording",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Navy900
                        )
                        Text(
                            text = policy.agencyName,
                            fontSize = 13.sp,
                            color = TextSecondaryLight
                        )
                    }
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = BlueAccent
                        )
                    } else {
                        IconButton(onClick = { viewModel.refreshPolicy() }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh Policy",
                                tint = BlueAccent
                            )
                        }
                    }
                }
            }

            // Consent Notice Card
            item {
                ConsentNoticeCard(
                    consentText = policy.consentNoticeText,
                    isAcknowledged = consentAcknowledged,
                    requireReminder = policy.requireConsentReminder,
                    onAcknowledge = { viewModel.acknowledgeConsent() }
                )
            }

            // DNR Policy Card
            item {
                DnrPolicyCard(
                    enforceStrictDnr = policy.enforceStrictDnr
                )
            }

            // Pause Recording Sync Card
            if (policy.allowRecordingPause) {
                item {
                    PauseSyncCard(
                        isPaused = policy.recordingSyncPaused,
                        isLoading = uiState.pauseToggleLoading,
                        onToggle = { viewModel.togglePauseSync() }
                    )
                }
            }

            // What Was Uploaded Section
            item {
                Text(
                    text = "What Was Uploaded",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Navy900,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = "Server-confirmed recording uploads",
                    fontSize = 12.sp,
                    color = TextSecondaryLight
                )
            }

            val confirmedUploads = uploadHistory.filter {
                it.status == UploadTaskEntity.STATUS_COMPLETED ||
                it.status == UploadTaskEntity.STATE_UPLOADED
            }

            if (confirmedUploads.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = LightSurface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.CloudUpload,
                                contentDescription = null,
                                tint = TextTertiaryLight,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No recordings uploaded yet",
                                color = TextSecondaryLight,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            } else {
                items(confirmedUploads, key = { it.id }) { task ->
                    UploadHistoryItem(task = task)
                }
            }

            // Policy Sync Info Footer
            item {
                if (policy.lastSyncEpochMs > 0) {
                    val formatted = remember(policy.lastSyncEpochMs) {
                        SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                            .format(Date(policy.lastSyncEpochMs))
                    }
                    Text(
                        text = "Policy last synced: $formatted",
                        fontSize = 11.sp,
                        color = TextTertiaryLight,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsentNoticeCard(
    consentText: String,
    isAcknowledged: Boolean,
    requireReminder: Boolean,
    onAcknowledge: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isAcknowledged) LightSurface else AmberWarning.copy(alpha = 0.08f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isAcknowledged) Icons.Default.CheckCircle else Icons.Default.Info,
                    contentDescription = null,
                    tint = if (isAcknowledged) TealSuccess else AmberWarning,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Privacy Notice",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Navy900
                )
                if (isAcknowledged) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = TealSuccess.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "ACKNOWLEDGED",
                            color = TealSuccess,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            val displayText = consentText.ifBlank {
                "In compliance with telecom regulations and agency policy, calls with assigned leads may be recorded for quality and training purposes. Calls with non-lead contacts and Do-Not-Record (DNR) designated leads are never recorded or uploaded."
            }
            Text(
                text = displayText,
                fontSize = 13.sp,
                color = TextSecondaryLight,
                lineHeight = 18.sp
            )

            if (!isAcknowledged && requireReminder) {
                Button(
                    onClick = onAcknowledge,
                    colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("I Understand & Acknowledge")
                }
            }
        }
    }
}

@Composable
private fun DnrPolicyCard(enforceStrictDnr: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (enforceStrictDnr) RedDanger.copy(alpha = 0.12f) else TealSuccess.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (enforceStrictDnr) RedDanger else TealSuccess,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Do-Not-Record (DNR) Policy",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Navy900
                    )
                    Text(
                        text = if (enforceStrictDnr) "Strict enforcement active" else "Standard enforcement",
                        fontSize = 12.sp,
                        color = if (enforceStrictDnr) RedDanger else TextSecondaryLight
                    )
                }
            }

            Text(
                text = "Leads marked as DNR will never have their call recordings opened, processed, or uploaded. " +
                        "DNR checks run before any recording file access.",
                fontSize = 13.sp,
                color = TextSecondaryLight,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun PauseSyncCard(
    isPaused: Boolean,
    isLoading: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isPaused) AmberWarning.copy(alpha = 0.08f) else LightSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Recording Sync",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Navy900
                )
                Text(
                    text = if (isPaused) "Paused — recordings will not be uploaded"
                    else "Active — recordings are uploaded automatically",
                    fontSize = 12.sp,
                    color = if (isPaused) AmberWarning else TextSecondaryLight
                )
            }

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = BlueAccent
                )
            } else {
                Switch(
                    checked = !isPaused,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = TealSuccess,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = TextTertiaryLight
                    )
                )
            }
        }
    }
}

@Composable
private fun UploadHistoryItem(task: UploadTaskEntity) {
    val dateText = remember(task.updatedAtEpochMs) {
        SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            .format(Date(task.updatedAtEpochMs))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(TealSuccess.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.CloudDone,
                    contentDescription = null,
                    tint = TealSuccess,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.fileName,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = Navy900,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.callId != null) {
                        Text(
                            text = "Call: ${task.callId.take(8)}…",
                            fontSize = 11.sp,
                            color = TextTertiaryLight
                        )
                    }
                    Text(
                        text = formatFileSize(task.fileSizeBytes),
                        fontSize = 11.sp,
                        color = TextTertiaryLight
                    )
                }
            }

            Text(
                text = dateText,
                fontSize = 11.sp,
                color = TextTertiaryLight
            )
        }
    }
}

@Composable
private fun ConsentNoticeDialog(
    consentText: String,
    onAcknowledge: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.PrivacyTip,
                contentDescription = null,
                tint = BlueAccent,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                "Privacy & Recording Notice",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = consentText.ifBlank {
                    "In compliance with telecom regulations and agency policy, calls with assigned leads may be recorded for quality and training purposes. Calls with non-lead contacts and Do-Not-Record (DNR) designated leads are never recorded or uploaded."
                },
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onAcknowledge,
                colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
            ) {
                Text("I Understand")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Later", color = TextSecondaryLight)
            }
        }
    )
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
