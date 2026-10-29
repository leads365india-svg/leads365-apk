package com.leads365.apk.ui.upload

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadStatusScreen(
    viewModel: UploadStatusViewModel = hiltViewModel(),
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val tasks by viewModel.tasks.collectAsState()
    val wifiOnly by viewModel.wifiOnly.collectAsState()
    val selectedTaskIds by viewModel.selectedTaskIds.collectAsState()

    val uploadedCount = tasks.count {
        it.status == UploadTaskEntity.STATE_UPLOADED || it.status == UploadTaskEntity.STATUS_COMPLETED
    }
    val inProgressCount = tasks.count {
        it.status == UploadTaskEntity.STATE_UPLOADING || it.status == UploadTaskEntity.STATUS_IN_PROGRESS
    }
    val eligibleTasks = tasks.filter { viewModel.isTaskEligibleForUpload(it) }
    val selectedCount = selectedTaskIds.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Recording Upload Pipeline",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Navy900
                        )
                        Text(
                            text = "${tasks.size} records • $uploadedCount uploaded • $inProgressCount uploading",
                            fontSize = 11.sp,
                            color = TextSecondaryLight
                        )
                    }
                },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Navy900
                            )
                        }
                    }
                },
                actions = {
                    if (uploadedCount > 0) {
                        IconButton(onClick = { viewModel.clearCompleted() }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear Completed",
                                tint = TextSecondaryLight
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Wi-Fi Only Preference Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = LightSurface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Upload on Wi-Fi Only",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Navy900
                            )
                        }
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = { viewModel.setWifiOnly(it) }
                        )
                    }
                }
            }

            // Selection & Batch Action Toolbar (only displayed when tasks exist)
            if (tasks.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Slate100),
                        border = BorderStroke(1.dp, Slate200)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    if (selectedCount == eligibleTasks.size && eligibleTasks.isNotEmpty()) {
                                        viewModel.clearSelection()
                                    } else {
                                        viewModel.selectAllEligible()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (selectedCount == eligibleTasks.size && eligibleTasks.isNotEmpty()) {
                                        "Deselect All"
                                    } else {
                                        "Select All Eligible (${eligibleTasks.size})"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Blue600
                                )
                            }

                            Button(
                                onClick = { viewModel.uploadSelectedTasks() },
                                enabled = selectedCount > 0,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Blue600,
                                    disabledContainerColor = Slate300
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (selectedCount > 0) "Upload Selected ($selectedCount)" else "Upload Selected",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            if (tasks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = null,
                                tint = TextSecondaryLight,
                                modifier = Modifier.size(54.dp)
                            )
                            Text(
                                text = "No Recording Uploads Yet",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Navy900
                            )
                            Text(
                                text = "Eligible lead calls with matching recordings will appear here and upload automatically.",
                                fontSize = 12.sp,
                                color = TextSecondaryLight,
                                modifier = Modifier.padding(horizontal = 32.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(tasks, key = { it.id }) { task ->
                    val isEligible = viewModel.isTaskEligibleForUpload(task)
                    val isSelected = selectedTaskIds.contains(task.id)

                    UploadTaskCard(
                        task = task,
                        isEligible = isEligible,
                        isSelected = isSelected,
                        onToggleSelect = { viewModel.toggleTaskSelection(task.id) },
                        onRetry = { viewModel.retryTask(task) }
                    )
                }
            }
        }
    }
}

@Composable
fun UploadTaskCard(
    task: UploadTaskEntity,
    isEligible: Boolean = true,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onRetry: () -> Unit
) {
    val dateStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(task.updatedAtEpochMs))

    val (badgeBg, badgeFg, badgeText, badgeIcon) = getBadgeAttributes(task.status)

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
            // Header Row: Checkbox, File Name & Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isEligible) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelect() },
                            colors = CheckboxDefaults.colors(checkedColor = Blue600)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(badgeBg.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (task.status == UploadTaskEntity.STATE_UPLOADING || task.status == UploadTaskEntity.STATUS_IN_PROGRESS) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = BlueAccent
                            )
                        } else {
                            Icon(
                                imageVector = badgeIcon,
                                contentDescription = null,
                                tint = badgeFg,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = if (task.fileName.isNotBlank()) task.fileName else "Call Recording",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Navy900,
                            maxLines = 1
                        )
                        val sizeFormatted = formatFileSize(task.fileSizeBytes)
                        Text(
                            text = if (sizeFormatted.isNotBlank()) "$sizeFormatted • $dateStr" else dateStr,
                            fontSize = 11.sp,
                            color = TextSecondaryLight
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = badgeBg
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Call ID / Lead details
            if (!task.callId.isNullOrBlank()) {
                Text(
                    text = "Call ID: ${task.callId}",
                    fontSize = 11.sp,
                    color = TextSecondaryLight
                )
            }

            // Error info if present
            if (!task.lastError.isNullOrBlank()) {
                Text(
                    text = "Reason: ${task.lastError}",
                    fontSize = 11.sp,
                    color = RedDanger,
                    fontWeight = FontWeight.Medium
                )
            }

            // Retry count and Action button Row
            val isRetryable = task.status == UploadTaskEntity.STATE_RETRY_WAIT ||
                    task.status == UploadTaskEntity.STATE_FAILED_PERMANENT ||
                    task.status == UploadTaskEntity.STATE_REJECTED_BY_SERVER ||
                    task.status == UploadTaskEntity.STATE_FILE_FOUND ||
                    task.status == UploadTaskEntity.STATUS_FAILED

            if (isRetryable || task.retryCount > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (task.retryCount > 0) {
                        Text(
                            text = "Attempts: ${task.retryCount}/${task.maxRetries}",
                            fontSize = 10.sp,
                            color = TextSecondaryLight
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    if (isRetryable) {
                        OutlinedButton(
                            onClick = onRetry,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Retry", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun getBadgeAttributes(status: String): Quadruple<Color, Color, String, ImageVector> {
    return when (status) {
        UploadTaskEntity.STATE_UPLOADED,
        UploadTaskEntity.STATUS_COMPLETED -> Quadruple(
            TealSuccess.copy(alpha = 0.15f),
            TealSuccess,
            "Uploaded",
            Icons.Default.CheckCircle
        )
        UploadTaskEntity.STATE_UPLOADING,
        UploadTaskEntity.STATUS_IN_PROGRESS -> Quadruple(
            BlueAccent.copy(alpha = 0.15f),
            BlueAccent,
            "Uploading",
            Icons.Default.CloudUpload
        )
        UploadTaskEntity.STATE_RETRY_WAIT -> Quadruple(
            Color(0xFFFEF3C7),
            Color(0xFFD97706),
            "Retry Wait",
            Icons.Default.Schedule
        )
        UploadTaskEntity.STATE_FILE_FOUND,
        UploadTaskEntity.STATUS_PENDING -> Quadruple(
            BlueAccent.copy(alpha = 0.1f),
            BlueAccent,
            "Queued",
            Icons.Default.Schedule
        )
        UploadTaskEntity.STATE_REJECTED_BY_SERVER -> Quadruple(
            RedDanger.copy(alpha = 0.15f),
            RedDanger,
            "Rejected",
            Icons.Default.Block
        )
        UploadTaskEntity.STATE_FAILED_PERMANENT,
        UploadTaskEntity.STATUS_FAILED -> Quadruple(
            RedDanger.copy(alpha = 0.15f),
            RedDanger,
            "Failed",
            Icons.Default.ErrorOutline
        )
        UploadTaskEntity.STATE_NO_FILE_FOUND -> Quadruple(
            Color(0xFFF1F5F9),
            TextSecondaryLight,
            "No File Found",
            Icons.Default.Info
        )
        UploadTaskEntity.STATE_AMBIGUOUS -> Quadruple(
            Color(0xFFFEF3C7),
            Color(0xFFD97706),
            "Ambiguous",
            Icons.Default.Warning
        )
        UploadTaskEntity.STATE_SKIPPED_DNR -> Quadruple(
            Color(0xFFF1F5F9),
            TextSecondaryLight,
            "DNR Skipped",
            Icons.Default.Close
        )
        UploadTaskEntity.STATE_SKIPPED_NOT_LEAD -> Quadruple(
            Color(0xFFF1F5F9),
            TextSecondaryLight,
            "Not Lead",
            Icons.Default.Close
        )
        else -> Quadruple(
            Color(0xFFF1F5F9),
            TextSecondaryLight,
            status,
            Icons.Default.Info
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        String.format(Locale.US, "%.0f KB", kb)
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
