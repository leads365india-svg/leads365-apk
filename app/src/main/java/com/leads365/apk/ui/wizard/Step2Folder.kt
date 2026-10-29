package com.leads365.apk.ui.wizard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leads365.apk.core.PrivacyRedactor
import com.leads365.apk.core.RecordingDiscoveryMethod
import com.leads365.apk.recordings.SafFolderInspection
import com.leads365.apk.ui.theme.*

@Composable
fun Step2Folder(
    safConfigured: Boolean,
    safValid: Boolean,
    safUriString: String?,
    safInspection: SafFolderInspection?,
    mediaStoreAccessible: Boolean,
    activeMethod: RecordingDiscoveryMethod,
    folderQuerySuccess: Boolean?,
    folderQueryMessage: String,
    canContinue: Boolean,
    deviceModel: String,
    onPickFolder: () -> Unit,
    onClearFolder: () -> Unit,
    onVerifyFolder: () -> Unit,
    onSelectMethod: (RecordingDiscoveryMethod) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayFolder = remember(safUriString) {
        if (safUriString == null) "No folder selected"
        else {
            try {
                val decoded = java.net.URLDecoder.decode(safUriString, "UTF-8")
                val treePart = decoded.substringAfter("/tree/", decoded)
                if (treePart.contains(":")) treePart.substringAfter(":") else treePart
            } catch (e: Exception) {
                PrivacyRedactor.redactFilename(safUriString)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section Heading
        Text(
            text = "Recording Folder",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Slate900
        )

        // White Bordered Folder Card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Slate200),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header row: Title + Connected badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Call Recordings Folder",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Slate900
                    )

                    val (statusBg, statusFg, statusText) = when {
                        safConfigured && safValid -> Triple(Color(0xFFDCFCE7), Color(0xFF15803D), "Connected")
                        safConfigured && !safValid -> Triple(Color(0xFFFEE2E2), RedDanger, "Permission Expired")
                        else -> Triple(Color(0xFFFEF3C7), Color(0xFFB45309), "Not Selected")
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = statusBg
                    ) {
                        Text(
                            text = statusText,
                            color = statusFg,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                        )
                    }
                }

                // Monospace Folder Path Surface
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Slate50,
                    border = BorderStroke(1.dp, Slate200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (safConfigured && safValid) {
                            "content://.../$displayFolder"
                        } else {
                            "No recording folder configured. Tap 'Select Folder' below."
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = if (safConfigured && safValid) Slate700 else Slate400,
                        modifier = Modifier.padding(10.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Recording Discovery / Inspection Status Box
                if (safConfigured && safValid && safInspection != null) {
                    val hasAudio = safInspection.audioFilesCount > 0
                    val isWrong = safInspection.isLikelyWrongFolder
                    val boxBg = when {
                        hasAudio -> Emerald50
                        isWrong -> Rose50
                        else -> Amber50
                    }
                    val boxBorder = when {
                        hasAudio -> Emerald200
                        isWrong -> Rose200
                        else -> Amber200
                    }
                    val iconTint = when {
                        hasAudio -> Emerald600
                        isWrong -> RedDanger
                        else -> Amber700
                    }
                    val textFg = when {
                        hasAudio -> Emerald800
                        isWrong -> Rose800
                        else -> Amber900
                    }
                    val headerText = when {
                        hasAudio -> "Audio Recordings Detected (${safInspection.audioFilesCount} files)"
                        isWrong -> "Wrong Folder Selected"
                        safInspection.totalFilesCount == 0 -> "Folder is empty"
                        else -> "No audio recordings found"
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = boxBg,
                        border = BorderStroke(1.dp, boxBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (hasAudio) Icons.Default.CheckCircle else if (isWrong) Icons.Default.Warning else Icons.Default.Info,
                                    contentDescription = null,
                                    tint = iconTint,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = headerText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textFg
                                )
                            }
                            Text(
                                text = safInspection.messageEn,
                                fontSize = 11.sp,
                                color = textFg,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }

                // Action Buttons Row
                if (safConfigured && safValid) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onVerifyFolder,
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Slate300)
                        ) {
                            Text("Check Access", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Slate700)
                        }

                        Button(
                            onClick = onPickFolder,
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Slate900)
                        ) {
                            Text("Change Folder", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                } else {
                    Button(
                        onClick = onPickFolder,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Slate900)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select Recording Folder", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Automatic MediaStore Scanner Alternative Card (if supported)
        if (mediaStoreAccessible) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color.White,
                border = BorderStroke(1.dp, Slate200)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Alternative: Automatic Media Scanner",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = Slate900
                        )
                        Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFDCFCE7)) {
                            Text(
                                text = "Available",
                                color = Color(0xFF15803D),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (activeMethod != RecordingDiscoveryMethod.MEDIA_STORE) {
                        TextButton(
                            onClick = { onSelectMethod(RecordingDiscoveryMethod.MEDIA_STORE) },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Switch to Automatic MediaScanner →", fontSize = 11.sp, color = Blue600, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Text("Active discovery method: MediaStore Scanner", fontSize = 11.sp, color = Emerald700, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // Bottom Navigation Row: Back (1/3) + Continue to Step 3 (2/3)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Slate200,
                    contentColor = Slate700
                )
            ) {
                Text("Back", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onContinue,
                enabled = canContinue,
                modifier = Modifier
                    .weight(2f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Emerald800,
                    disabledContainerColor = Slate200,
                    disabledContentColor = Slate500
                )
            ) {
                Text(
                    text = "Continue to Step 3 →",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (canContinue) Color.White else Slate500
                )
            }
        }
    }
}
