package com.leads365.apk.ui.wizard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leads365.apk.core.*
import com.leads365.apk.ui.theme.*

@Composable
fun Step4TestResult(
    recentCallEvent: CallEvent?,
    isCallSimulated: Boolean,
    gateResult: GateEvaluationResult?,
    matchingResult: RecordingMatchResult?,
    candidateRecordings: List<RecordingCandidate> = emptyList(),
    isSearchingRecording: Boolean,
    recordingDiscoveryState: com.leads365.apk.ui.RecordingDiscoveryState = com.leads365.apk.ui.RecordingDiscoveryState.IDLE,
    uploadState: UploadState,
    deviceModel: String,
    onStartNewTest: () -> Unit,
    onRequestUpload: (RecordingCandidate) -> Unit,
    onRetryUpload: () -> Unit,
    onOpenAdvancedDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header (English only)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Test Result",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Navy900,
                    fontSize = 18.sp
                )
                Text(
                    text = "Step 4 of 4",
                    fontSize = 12.sp,
                    color = BlueAccent,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Stage 1: Call-Log Event Detected Banner (Clear distinction from audio file existence)
            if (recentCallEvent != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFF1F5F9),
                    border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                    modifier = Modifier.fillMaxWidth()
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
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.PhoneCallback, contentDescription = null, tint = Navy800, modifier = Modifier.size(16.dp))
                                Text("Stage 1: Call-Log Event Detected", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Navy900)
                            }
                            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFE2E8F0)) {
                                Text(
                                    text = if (isCallSimulated) "DEMO EVENT" else "CALL LOGGED",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Navy800,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Number: ${PrivacyRedactor.redactPhoneNumber(recentCallEvent.normalizedNumber)} • Direction: ${recentCallEvent.direction} • Duration: ${recentCallEvent.durationSeconds}s",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Navy900
                        )
                        Text(
                            text = "Notice: Call-log detection confirms the call ended. It is NOT proof that an audio recording file exists until dialer discovery completes below.",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }
            }

            // High-Prominence Result Card based on exact platform outcome
            when {
                // Phone Access / Gate Stopped
                gateResult is GateEvaluationResult.RejectedNotALead -> {
                    ResultOutcomeCard(
                        statusTitle = "Call Stopped by Lead-Only Gate",
                        description = "The call was from an unconfigured number. Leads365 strictly ignores all non-lead calls to protect privacy. No recording search occurred.",
                        badgeText = "NON-LEAD STOPPED",
                        badgeBg = Color(0xFFF1F5F9),
                        badgeFg = TextSecondaryLight,
                        icon = Icons.Default.Shield,
                        iconTint = BlueAccent
                    )
                }
                gateResult is GateEvaluationResult.RejectedDoNotRecord -> {
                    ResultOutcomeCard(
                        statusTitle = "Recording Blocked (Do-Not-Record)",
                        description = "This lead is marked Do-Not-Record. Leads365 halted before opening or querying any folder to guarantee compliance.",
                        badgeText = "DNR BLOCKED",
                        badgeBg = Color(0xFFFEE2E2),
                        badgeFg = RedDanger,
                        icon = Icons.Default.Block,
                        iconTint = RedDanger
                    )
                }
                gateResult is GateEvaluationResult.RejectedDuplicateCall -> {
                    ResultOutcomeCard(
                        statusTitle = "Duplicate Call Event Ignored",
                        description = "A duplicate call event was detected within 15 seconds. Stopped to avoid duplicate processing.",
                        badgeText = "DUPLICATE IGNORED",
                        badgeBg = Color(0xFFFEF3C7),
                        badgeFg = Color(0xFFB45309),
                        icon = Icons.Default.History,
                        iconTint = Color(0xFFB45309)
                    )
                }
                // Folder Access Required
                recordingDiscoveryState == com.leads365.apk.ui.RecordingDiscoveryState.FOLDER_ACCESS_REQUIRED -> {
                    ResultOutcomeCard(
                        statusTitle = "Folder access required",
                        description = "A selected recording folder with valid read permission is required before recordings can be searched. Please grant folder access in Step 2.",
                        badgeText = "FOLDER ACCESS REQUIRED",
                        badgeBg = Color(0xFFFEF3C7),
                        badgeFg = Color(0xFFB45309),
                        icon = Icons.Default.Folder,
                        iconTint = Color(0xFFB45309)
                    )
                }
                // Recording Unavailable On This Device
                recordingDiscoveryState == com.leads365.apk.ui.RecordingDiscoveryState.RECORDING_UNAVAILABLE_ON_THIS_DEVICE -> {
                    ResultOutcomeCard(
                        statusTitle = "Recording unavailable on this device",
                        description = "This phone ($deviceModel) uses a sandboxed phone dialer that stores call audio inside private system storage, preventing direct file access. Cloud telephony fallback will be used.",
                        badgeText = "DIALER SANDBOXED",
                        badgeBg = Color(0xFFFEE2E2),
                        badgeFg = RedDanger,
                        icon = Icons.Default.Warning,
                        iconTint = RedDanger
                    )
                }
                // Recording Lookup Failed
                recordingDiscoveryState == com.leads365.apk.ui.RecordingDiscoveryState.RECORDING_LOOKUP_FAILED -> {
                    ResultOutcomeCard(
                        statusTitle = "Recording lookup failed",
                        description = "An I/O or security error occurred while reading the recording folder. Please check your storage permission or re-select the folder in Step 2.",
                        badgeText = "LOOKUP FAILED",
                        badgeBg = Color(0xFFFEE2E2),
                        badgeFg = RedDanger,
                        icon = Icons.Default.Error,
                        iconTint = RedDanger
                    )
                }
                // Stage 2: Waiting for Recording
                isSearchingRecording || recordingDiscoveryState == com.leads365.apk.ui.RecordingDiscoveryState.WAITING_FOR_RECORDING -> {
                    ResultOutcomeCard(
                        statusTitle = "Stage 2: Waiting for dialer to write recording",
                        description = "Phone dialers (including Vivo $deviceModel) buffer audio and write the file to storage a few seconds after hang-up. Leads365 is actively polling the selected folder...",
                        badgeText = "POLLING STORAGE...",
                        badgeBg = Color(0xFFFEF3C7),
                        badgeFg = Color(0xFFB45309),
                        icon = Icons.Default.HourglassTop,
                        iconTint = Color(0xFFB45309)
                    )
                }
                // Stage 3: Recording Found & Matched (Success!)
                (recordingDiscoveryState == com.leads365.apk.ui.RecordingDiscoveryState.RECORDING_FOUND || matchingResult?.status == MatchStatus.FILE_FOUND) && matchingResult?.matchedCandidate != null -> {
                    val candidate = matchingResult.matchedCandidate
                    RecordingFoundCard(
                        candidate = candidate,
                        isSimulated = isCallSimulated,
                        uploadState = uploadState,
                        onRequestUpload = { onRequestUpload(candidate) },
                        onRetryUpload = onRetryUpload
                    )
                }
                // Recording Inaccessible / Unreadable
                recordingDiscoveryState == com.leads365.apk.ui.RecordingDiscoveryState.RECORDING_UNAVAILABLE_OR_INACCESSIBLE || matchingResult?.status == MatchStatus.INACCESSIBLE -> {
                    val specificReason = matchingResult?.reason?.ifBlank { "A matching candidate was found in storage catalog, but the actual file cannot be opened or read." }
                        ?: "A matching candidate was found in storage catalog, but the actual file cannot be opened or read."
                    ResultOutcomeCard(
                        statusTitle = "Recording Inaccessible / Unreadable",
                        description = "Reason: $specificReason\n\nTroubleshooting:\n• The recording file might have been moved, deleted, or incomplete (0-byte).\n• Storage read permission may have expired.\n• Check File Manager in the dialer's native recording folder.",
                        badgeText = "UNVERIFIED / UNREADABLE",
                        badgeBg = Color(0xFFFEE2E2),
                        badgeFg = RedDanger,
                        icon = Icons.Default.ErrorOutline,
                        iconTint = RedDanger
                    )
                }
                // Multiple possible recordings
                recordingDiscoveryState == com.leads365.apk.ui.RecordingDiscoveryState.MULTIPLE_POSSIBLE_RECORDINGS || matchingResult?.status == MatchStatus.AMBIGUOUS -> {
                    ResultOutcomeCard(
                        statusTitle = "Multiple possible recordings",
                        description = "Multiple audio files were created around this call time. Leads365 stops here to avoid attaching the wrong recording to a CRM lead.",
                        badgeText = "AMBIGUOUS MATCH",
                        badgeBg = Color(0xFFFEF3C7),
                        badgeFg = Color(0xFFB45309),
                        icon = Icons.Default.Warning,
                        iconTint = Color(0xFFB45309)
                    )
                }
                // Stage 4: No Recording Found with specific reason & next steps
                recordingDiscoveryState == com.leads365.apk.ui.RecordingDiscoveryState.NO_RECORDING_FOUND || matchingResult?.status == MatchStatus.NO_FILE_FOUND -> {
                    val specificReason = matchingResult?.reason?.ifBlank { "No audio file created in the selected folder within the call window" }
                        ?: "No audio file created in the selected folder within the call window"
                    ResultOutcomeCard(
                        statusTitle = "Stage 4: No recording found",
                        description = "Reason: $specificReason\n\nNext Steps:\n1. Open Phone dialer > Settings > Record settings > Select 'Record all calls automatically'.\n2. In Step 2, ensure the selected folder is 'Recordings/Call' on internal storage.\n3. Vivo dialers may delay writing the file by 30–60 seconds; tap 'Check for Recent Call' in Step 3.",
                        badgeText = "NO RECORDING DETECTED",
                        badgeBg = Color(0xFFF1F5F9),
                        badgeFg = TextSecondaryLight,
                        icon = Icons.Default.SearchOff,
                        iconTint = TextSecondaryLight
                    )
                }
                // Fallback / Awaiting
                else -> {
                    ResultOutcomeCard(
                        statusTitle = "Awaiting Test Results",
                        description = "Make a call in Step 3 to see compatibility results here.",
                        badgeText = "PENDING",
                        badgeBg = Color(0xFFF1F5F9),
                        badgeFg = TextSecondaryLight,
                        icon = Icons.Default.Info,
                        iconTint = TextSecondaryLight
                    )
                }
            }

            // Dialer System Notice
            Surface(
                color = Color(0xFFF8FAFC),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = BlueAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Notice: The phone's dialer must support and generate call recordings. Leads365 cannot turn on system call recording automatically.",
                        fontSize = 11.sp,
                        color = TextSecondaryLight,
                        lineHeight = 15.sp
                    )
                }
            }

            // Retest & Advanced Options
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStartNewTest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Navy900)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Start New Test", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                TextButton(
                    onClick = onOpenAdvancedDiagnostics,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Developer Diagnostics & Telemetry", fontSize = 11.sp, color = BlueAccent)
                }
            }
        }
    }
}

@Composable
private fun ResultOutcomeCard(
    statusTitle: String,
    description: String,
    badgeText: String,
    badgeBg: Color,
    badgeFg: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                    Text(statusTitle, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Navy900)
                }

                Surface(shape = RoundedCornerShape(6.dp), color = badgeBg) {
                    Text(
                        text = badgeText,
                        color = badgeFg,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun RecordingFoundCard(
    candidate: RecordingCandidate,
    isSimulated: Boolean,
    uploadState: UploadState,
    onRequestUpload: () -> Unit,
    onRetryUpload: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF0FDF4),
        border = BorderStroke(1.dp, Color(0xFF86EFAC))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isSimulated) Icons.Default.Info else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (isSimulated) Color(0xFFB45309) else Color(0xFF15803D),
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = if (isSimulated) "Simulated Event (Test Only)" else "Audio Recording Found!",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (isSimulated) Color(0xFFB45309) else Color(0xFF15803D)
                        )
                        if (isSimulated) {
                            Text("Demo simulation — Hardware recording remains unverified", fontSize = 10.sp, color = Color(0xFF92400E), fontWeight = FontWeight.Bold)
                        } else {
                            Text("Matched from physical dialer", fontSize = 11.sp, color = Color(0xFF166534))
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isSimulated) Color(0xFFFEF3C7) else Color(0xFFDCFCE7)
                ) {
                    Text(
                        text = if (isSimulated) "SIMULATED" else "VERIFIED",
                        color = if (isSimulated) Color(0xFFB45309) else Color(0xFF15803D),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // File Details Card
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.White,
                border = BorderStroke(1.dp, Color(0xFFBBF7D0)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "File: ${PrivacyRedactor.redactFilename(candidate.displayName)}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color(0xFF15803D)
                    )
                    Text(
                        text = "Size: ${TimeHelpers.formatSize(candidate.sizeBytes)} • Type: ${candidate.mimeType}",
                        fontSize = 10.sp,
                        color = TextSecondaryLight
                    )
                    Text(
                        text = "Source: ${if (candidate.sourceMethod == RecordingDiscoveryMethod.MEDIA_STORE) "MediaStore (System Audio Index)" else "Selected Folder (SAF)"}",
                        fontSize = 10.sp,
                        color = TextSecondaryLight
                    )
                    if (!candidate.relativePath.isNullOrBlank()) {
                        Text(
                            text = "Storage Folder: ${candidate.relativePath}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1E40AF)
                        )
                    }
                    Text(
                        text = "Status: Verified Readable (${TimeHelpers.formatSize(candidate.sizeBytes)} confirmed)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF15803D)
                    )
                }
            }

            if (candidate.sourceMethod == RecordingDiscoveryMethod.MEDIA_STORE) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFEFF6FF),
                    border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(16.dp))
                        Text(
                            text = "Notice: The recording was found in the phone's native dialer location (${candidate.relativePath ?: "Recordings/Call"}). The custom folder selected in Step 2 is empty.",
                            fontSize = 10.sp,
                            color = Color(0xFF1E40AF),
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            Text(
                text = if (isSimulated) {
                    "This test simulation confirmed UI matching and privacy gating. Hardware recording discovery remains unverified until an actual physical call is recorded."
                } else {
                    "Your phone's native dialer call recording was successfully detected and matched to the test lead without audio tapping or root."
                },
                fontSize = 11.sp,
                color = if (isSimulated) Color(0xFF92400E) else Color(0xFF166534),
                lineHeight = 15.sp
            )

            // Upload Status / Action
            when (uploadState) {
                is UploadState.Idle -> {
                    Button(
                        onClick = onRequestUpload,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF15803D)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test Upload", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                is UploadState.Uploading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Streaming Audio...", fontSize = 11.sp, color = Color(0xFF15803D), fontWeight = FontWeight.Bold)
                            Text("${uploadState.progressPercent}%", fontSize = 11.sp, color = Color(0xFF15803D))
                        }
                        LinearProgressIndicator(
                            progress = { uploadState.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = TealSuccess
                        )
                    }
                }
                is UploadState.Success -> {
                    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFDCFCE7), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF15803D), modifier = Modifier.size(16.dp))
                            Text("Server Confirmed: Upload Complete (HTTP ${uploadState.httpStatusCode})", fontSize = 11.sp, color = Color(0xFF15803D), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                is UploadState.Failed -> {
                    val isLocalTestModeOrNoEndpoint = uploadState.errorMessage.contains("Local Recording Test Mode", ignoreCase = true) ||
                        uploadState.errorMessage.contains("login", ignoreCase = true) ||
                        uploadState.errorMessage.contains("No test upload endpoint", ignoreCase = true) ||
                        uploadState.errorMessage.contains("sign in to upload", ignoreCase = true) ||
                        uploadState.errorMessage.contains("Server upload is unavailable", ignoreCase = true)

                    if (isLocalTestModeOrNoEndpoint) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFFEF3C7),
                            border = BorderStroke(1.dp, Color(0xFFFCD34D)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFB45309), modifier = Modifier.size(20.dp))
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        "Recording matched locally. Server upload is unavailable in Local Recording Test Mode; sign in to upload.",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFB45309),
                                        softWrap = true
                                    )
                                    Text(
                                        "The native dialer recording was verified readable on this device. Cloud uploads require an authenticated agent session.",
                                        fontSize = 10.sp,
                                        color = Color(0xFF92400E),
                                        softWrap = true
                                    )
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Upload failed: ${uploadState.errorMessage}",
                                fontSize = 11.sp,
                                color = RedDanger,
                                modifier = Modifier.fillMaxWidth(),
                                softWrap = true
                            )
                            if (uploadState.isRetryable) {
                                Button(onClick = onRetryUpload, colors = ButtonDefaults.buttonColors(containerColor = RedDanger), shape = RoundedCornerShape(6.dp)) {
                                    Text("Retry Upload", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }
}
