package com.leads365.apk.ui.wizard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
            // Title & Subtitle (English only)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Recording Folder",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Navy900,
                    fontSize = 18.sp
                )
                Text(
                    text = "Step 2 of 4",
                    fontSize = 12.sp,
                    color = BlueAccent,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Select the folder where your phone's dialer saves call recordings. Leads365 needs read access to verify that recordings match test calls.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp
                )
            }

            // Prominent Folder Selector Box
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
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
                            text = "Call Recordings Folder",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        val (statusBg, statusFg, statusText) = when {
                            safConfigured && safValid -> Triple(Color(0xFFDCFCE7), Color(0xFF15803D), "Connected")
                            safConfigured && !safValid -> Triple(Color(0xFFFEE2E2), RedDanger, "Permission Expired")
                            else -> Triple(Color(0xFFFEF3C7), Color(0xFFB45309), "Not Selected")
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = statusBg
                        ) {
                            Text(
                                text = statusText,
                                color = statusFg,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (safConfigured && safValid && safUriString != null) {
                        Text(
                            text = "Selected: ${PrivacyRedactor.redactFilename(safUriString)}",
                            fontSize = 11.sp,
                            color = TextSecondaryLight,
                            lineHeight = 15.sp,
                            modifier = Modifier.fillMaxWidth(),
                            softWrap = true
                        )

                        // Folder Inspection Diagnostic Box
                        if (safInspection != null) {
                            val hasAudio = safInspection.audioFilesCount > 0
                            val isWrong = safInspection.isLikelyWrongFolder
                            val boxBg = when {
                                hasAudio -> Color(0xFFDCFCE7)
                                isWrong -> Color(0xFFFEE2E2)
                                else -> Color(0xFFFEF3C7)
                            }
                            val boxBorder = when {
                                hasAudio -> Color(0xFFBBF7D0)
                                isWrong -> Color(0xFFFECACA)
                                else -> Color(0xFFFDE68A)
                            }
                            val iconTint = when {
                                hasAudio -> Color(0xFF15803D)
                                isWrong -> RedDanger
                                else -> Color(0xFFB45309)
                            }
                            val textFg = when {
                                hasAudio -> Color(0xFF166534)
                                isWrong -> Color(0xFF991B1B)
                                else -> Color(0xFF92400E)
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
                                    modifier = Modifier.padding(10.dp),
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
                                            color = iconTint
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

                        // Actions for Configured Folder
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onVerifyFolder,
                                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Check Access", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }

                            Button(
                                onClick = onPickFolder,
                                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Navy800)
                            ) {
                                Text("Change Folder", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        // Prominent Folder Selection Action
                        Button(
                            onClick = onPickFolder,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select Recording Folder", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Text(
                            text = "How to select: In the system file picker, choose phone storage, open 'Recordings' or 'Call', and tap 'Use this folder'.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            // Accurate "Enable call recording" Guidance Section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFFF8FAFC),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = Navy800, modifier = Modifier.size(18.dp))
                        Text(
                            text = "Enable call recording",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Navy900
                        )
                    }
                    Text(
                        text = "• Open your phone's native Phone/Dialer app settings and turn on automatic call recording, if supported by your dialer and region.\n" +
                                "• Leads365 cannot turn on system call recording directly due to Android security restrictions. Availability depends on your device manufacturer, dialer app, and regional policies.\n" +
                                "• If automatic recording is not available in your phone's dialer, the phone may not be able to provide recordings for Leads365 to match.",
                        fontSize = 11.sp,
                        color = TextSecondaryLight,
                        lineHeight = 16.sp
                    )
                }
            }

            // Simple MediaStore Alternative
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFFF8FAFC),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
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
                            text = "Alternative: Automatic Media Scanner",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = Navy900
                        )
                        if (mediaStoreAccessible) {
                            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFDCFCE7)) {
                                Text("Available", color = Color(0xFF15803D), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                            }
                        }
                    }
                    Text(
                        text = "If your phone dialer saves directly to public media audio, Leads365 can search automatically without folder selection.",
                        fontSize = 11.sp,
                        color = TextSecondaryLight,
                        lineHeight = 15.sp
                    )
                    if (activeMethod != RecordingDiscoveryMethod.MEDIA_STORE && mediaStoreAccessible) {
                        TextButton(
                            onClick = { onSelectMethod(RecordingDiscoveryMethod.MEDIA_STORE) },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Switch to Automatic MediaScanner →", fontSize = 11.sp, color = BlueAccent)
                        }
                    }
                }
            }

            // Navigation Row: Back and Continue
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Back", fontSize = 13.sp)
                }

                Button(
                    onClick = onContinue,
                    enabled = canContinue,
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TealSuccess,
                        disabledContainerColor = Color(0xFFCBD5E1)
                    )
                ) {
                    Text(
                        text = "Continue to Step 3 →",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (canContinue) Color.White else Color(0xFF64748B)
                    )
                }
            }
        }
    }
}
