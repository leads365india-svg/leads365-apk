package com.leads365.apk.ui.wizard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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
    onNavigateToCalls: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section Heading
        Text(
            text = "Test Result",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Slate900
        )

        // 1. Stage 1: Call-Log Event Summary Card
        if (recentCallEvent != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White,
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
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
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isCallSimulated) Slate500 else Emerald500)
                            )
                            Text(
                                text = "Stage 1: Call-Log Event Detected",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate900
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isCallSimulated) Slate200 else Emerald100
                        ) {
                            Text(
                                text = if (isCallSimulated) "DEMO EVENT" else "CALL LOGGED",
                                color = if (isCallSimulated) Slate700 else Emerald700,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Monospace call details box
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Slate50,
                        border = BorderStroke(1.dp, Slate200),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "Number: ${PrivacyRedactor.redactPhoneNumber(recentCallEvent.normalizedNumber)}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Slate700
                            )
                            Text(
                                text = "Direction: ${recentCallEvent.direction}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Slate700
                            )
                            Text(
                                text = "Duration: ${recentCallEvent.durationSeconds}s",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Slate700
                            )
                        }
                    }
                }
            }
        }

        // 2. Stage 2/3 Recording Match Status / Candidate File Details Card
        val matchedCandidate = matchingResult?.matchedCandidate
        val isMatchSuccess = (recordingDiscoveryState == com.leads365.apk.ui.RecordingDiscoveryState.RECORDING_FOUND ||
                matchingResult?.status == MatchStatus.FILE_FOUND) && matchedCandidate != null

        when {
            isMatchSuccess -> {
                val candidate = matchedCandidate ?: return@Column
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Emerald50,
                    border = BorderStroke(1.dp, Emerald200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isCallSimulated) "SIMULATED EVENT (TEST ONLY)" else "AUDIO RECORDING VERIFIED",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Emerald900
                            )
                            if (isCallSimulated) {
                                Surface(shape = RoundedCornerShape(4.dp), color = Amber100) {
                                    Text(
                                        text = "SIMULATED",
                                        color = Amber800,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = "File: ${PrivacyRedactor.redactFilename(candidate.displayName)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF064E3B)
                            )
                            Text(
                                text = "Size: ${TimeHelpers.formatSize(candidate.sizeBytes)} • Type: ${candidate.mimeType}",
                                fontSize = 11.sp,
                                color = Emerald800
                            )
                            Text(
                                text = "Source: ${if (candidate.sourceMethod == RecordingDiscoveryMethod.MEDIA_STORE) "MediaStore (System Audio Index)" else "Selected Folder (SAF)"}",
                                fontSize = 11.sp,
                                color = Emerald800
                            )
                            Text(
                                text = "Status: Verified Readable (${TimeHelpers.formatSize(candidate.sizeBytes)} confirmed)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Emerald700
                            )
                        }

                        // Real upload trigger and status
                        when (uploadState) {
                            is UploadState.Idle -> {
                                Button(
                                    onClick = { onRequestUpload(candidate) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Emerald700)
                                ) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Test Upload", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                            is UploadState.Uploading -> {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Streaming Audio...", fontSize = 11.sp, color = Emerald800, fontWeight = FontWeight.Bold)
                                        Text("${uploadState.progressPercent}%", fontSize = 11.sp, color = Emerald800)
                                    }
                                    LinearProgressIndicator(
                                        progress = { uploadState.progressPercent / 100f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp),
                                        color = Emerald600
                                    )
                                }
                            }
                            is UploadState.Success -> {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFDCFCE7),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = Emerald600, modifier = Modifier.size(16.dp))
                                        Text(
                                            text = "Server Confirmed: Upload Complete (HTTP ${uploadState.httpStatusCode})",
                                            fontSize = 11.sp,
                                            color = Emerald800,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            is UploadState.Failed -> {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Rose50,
                                        border = BorderStroke(1.dp, Rose200),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Upload failed: ${uploadState.errorMessage}",
                                            fontSize = 11.sp,
                                            color = Rose800,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                    if (uploadState.isRetryable) {
                                        Button(
                                            onClick = onRetryUpload,
                                            colors = ButtonDefaults.buttonColors(containerColor = RedDanger),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Text("Retry Upload", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }

            isSearchingRecording || recordingDiscoveryState == com.leads365.apk.ui.RecordingDiscoveryState.WAITING_FOR_RECORDING -> {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Amber50,
                    border = BorderStroke(1.dp, Amber200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Amber700)
                            Text(
                                text = "Stage 2: Waiting for dialer to write recording",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Amber900
                            )
                        }
                        Text(
                            text = "Phone dialers buffer audio and write the file to storage a few seconds after hang-up. Polling selected folder...",
                            fontSize = 11.sp,
                            color = Amber800
                        )
                    }
                }
            }

            gateResult is GateEvaluationResult.RejectedNotALead -> {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Slate100,
                    border = BorderStroke(1.dp, Slate200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Call Stopped by Lead-Only Gate", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Slate900)
                        Text(
                            "The call was from an unconfigured number. Leads365 strictly ignores all non-lead calls to protect privacy. No recording search occurred.",
                            fontSize = 11.sp,
                            color = Slate600
                        )
                    }
                }
            }

            gateResult is GateEvaluationResult.RejectedDoNotRecord -> {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Rose50,
                    border = BorderStroke(1.dp, Rose200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Recording Blocked (Do-Not-Record)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = RedDanger)
                        Text(
                            "This lead is marked Do-Not-Record. Leads365 halted before opening or querying any folder to guarantee compliance.",
                            fontSize = 11.sp,
                            color = Rose800
                        )
                    }
                }
            }

            recordingDiscoveryState == com.leads365.apk.ui.RecordingDiscoveryState.NO_RECORDING_FOUND || matchingResult?.status == MatchStatus.NO_FILE_FOUND -> {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Slate50,
                    border = BorderStroke(1.dp, Slate200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Stage 4: No Recording Found", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Slate900)
                        Text(
                            matchingResult?.reason ?: "No audio file created in the selected folder within the call window. Ensure auto-recording is enabled in dialer settings.",
                            fontSize = 11.sp,
                            color = Slate600
                        )
                    }
                }
            }

            else -> {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Slate50,
                    border = BorderStroke(1.dp, Slate200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Awaiting Test Results", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Slate900)
                        Text("Make a call in Step 3 to see compatibility results here.", fontSize = 11.sp, color = Slate600)
                    }
                }
            }
        }

        // 3. Start New Test Button
        Button(
            onClick = onStartNewTest,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Slate800
            ),
            border = BorderStroke(1.dp, Slate300)
        ) {
            Text("Start New Test", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        // 4. Go to Call History & Recordings Button (Blue-600)
        Button(
            onClick = onNavigateToCalls,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Blue600)
        ) {
            Icon(Icons.Default.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Go to Call History & Recordings →", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        // 5. Advanced Developer Diagnostics Link
        TextButton(
            onClick = onOpenAdvancedDiagnostics,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(14.dp), tint = Slate500)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Developer Diagnostics & Telemetry", fontSize = 11.sp, color = Slate500)
        }
    }
}
