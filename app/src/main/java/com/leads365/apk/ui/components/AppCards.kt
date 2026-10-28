package com.leads365.apk.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leads365.apk.core.*
import com.leads365.apk.ui.theme.*

/**
 * A. Compact Top Header
 * Designed for mobile phones: avoids oversized hero banners, presents branding,
 * short title, concise explanation, and compact device/OS chips without horizontal clipping.
 */
@Composable
fun AppHeaderCard(
    deviceModel: String,
    androidVersion: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Navy900),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Row 1: Brand Logo + Name + Phase Tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(BlueAccent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneInTalk,
                            contentDescription = "Leads365 Logo",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = "LEADS365",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        letterSpacing = 0.8.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Navy800,
                    border = BorderStroke(1.dp, Navy700)
                ) {
                    Text(
                        text = "DIAGNOSTICS & SETUP",
                        color = Color(0xFF93C5FD),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // Row 2: Short screen title
            Text(
                text = "Native Dialer Recording Setup",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                lineHeight = 22.sp
            )

            // Row 3: Concise 1-2 line explanation
            Text(
                text = "Configures native dialer recording access, permission gating, and lead-only matching for this device.",
                color = Color(0xFFCBD5E1),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )

            // Row 4: Device & OS info (flow-safe chips)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Navy800,
                    border = BorderStroke(1.dp, Navy700),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Smartphone,
                            contentDescription = null,
                            tint = BlueAccent,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = deviceModel.ifBlank { "Android Device" },
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Navy800,
                    border = BorderStroke(1.dp, Navy700)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Android,
                            contentDescription = null,
                            tint = TealSuccess,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = androidVersion.ifBlank { "OS" },
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * B. Compatibility Status Card
 * Simple, readable status card with clear status text, next required action banner,
 * and a clear progress checklist. Never displays 'Verified' unless conditions are satisfied.
 */
@Composable
fun CompatibilitySummaryCard(
    status: CompatibilityStatus,
    safReady: Boolean,
    mediaStoreReady: Boolean,
    hasCallObserved: Boolean,
    hasMatch: Boolean,
    hasUpload: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Title and Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Compatibility Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )

                val (badgeBg, badgeText, badgeColor) = when (status) {
                    CompatibilityStatus.NOT_TESTED -> Triple(Color(0xFFF1F5F9), "NOT TESTED", TextSecondaryLight)
                    CompatibilityStatus.NEEDS_ATTENTION -> Triple(Color(0xFFFEF3C7), "NEEDS ATTENTION", Color(0xFFB45309))
                    CompatibilityStatus.PARTIALLY_VERIFIED -> Triple(Color(0xFFE0F2FE), "PARTIALLY VERIFIED", Color(0xFF0369A1))
                    CompatibilityStatus.VERIFIED -> Triple(Color(0xFFDCFCE7), "VERIFIED", Color(0xFF15803D))
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeBg,
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Next Required Action Banner
            val (nextActionTitle, nextActionDesc, nextActionIcon, bannerBg, bannerBorder) = when {
                !safReady && !mediaStoreReady -> Tuple5(
                    "Action Required: Recording Access",
                    "Select dialer recordings folder (SAF) or grant MediaStore permission below.",
                    Icons.Default.FolderOpen,
                    Color(0xFFFEF3C7),
                    Color(0xFFFDE68A)
                )
                !hasCallObserved -> Tuple5(
                    "Action Required: Test Call",
                    "Make a cellular test call to your configured test lead, or tap Simulation #1.",
                    Icons.Default.Call,
                    Color(0xFFE0F2FE),
                    Color(0xFFBAE6FD)
                )
                !hasMatch -> Tuple5(
                    "Action Required: Recording Match",
                    "Awaiting candidate audio file in bounded call window matching duration/time.",
                    Icons.Default.HourglassTop,
                    Color(0xFFFEF3C7),
                    Color(0xFFFDE68A)
                )
                !hasUpload -> Tuple5(
                    "Action Required: Verify Streaming",
                    "Tap Upload on the matched recording to confirm chunked streaming to test server.",
                    Icons.Default.CloudUpload,
                    Color(0xFFE0F2FE),
                    Color(0xFFBAE6FD)
                )
                else -> Tuple5(
                    "Physical Lab Verification Complete",
                    "Software pipeline verified on this handset. Record results in compatibility matrix.",
                    Icons.Default.CheckCircle,
                    Color(0xFFDCFCE7),
                    Color(0xFFBBF7D0)
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = bannerBg,
                border = BorderStroke(1.dp, bannerBorder)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = nextActionIcon,
                        contentDescription = null,
                        tint = when (bannerBg) {
                            Color(0xFFDCFCE7) -> Color(0xFF15803D)
                            Color(0xFFE0F2FE) -> Color(0xFF0369A1)
                            else -> Color(0xFFB45309)
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = nextActionTitle,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = nextActionDesc,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            // Milestone Checklist
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MilestoneRow(title = "Telephony State & Call Log Access", isComplete = true, detail = "Granted")
                MilestoneRow(
                    title = "Dialer Recording Access (SAF/MediaStore)",
                    isComplete = safReady || mediaStoreReady,
                    detail = if (safReady) "SAF Persisted" else if (mediaStoreReady) "MediaStore" else "Not Selected"
                )
                MilestoneRow(
                    title = "Lead-Only Gate & Call-End Observation",
                    isComplete = hasCallObserved,
                    detail = if (hasCallObserved) "Observed" else "Pending Call"
                )
                MilestoneRow(
                    title = "Targeted Time & Duration Match",
                    isComplete = hasMatch,
                    detail = if (hasMatch) "Match Confirmed" else "Pending Match"
                )
                MilestoneRow(
                    title = "Safe Streaming Test Upload",
                    isComplete = hasUpload,
                    detail = if (hasUpload) "Verified (200)" else "Pending Upload"
                )
            }
        }
    }
}

private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

@Composable
private fun MilestoneRow(
    title: String,
    isComplete: Boolean,
    detail: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (isComplete) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isComplete) TealSuccess else TextTertiaryLight,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = if (isComplete) MaterialTheme.colorScheme.onSurface else TextSecondaryLight,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = detail,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isComplete) TealSuccess else TextTertiaryLight
        )
    }
}

/**
 * C. Setup Checklist (Permissions)
 * Clear vertical checklist with readable labels, short explanations, and an unmistakable action button.
 */
@Composable
fun PermissionChecklistCard(
    permissions: List<PermissionItem>,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val allMandatoryGranted = permissions.filter { it.isMandatory }.all { it.status == PermissionStatus.GRANTED }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
            Text(
                text = "Permissions & System Access",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                text = "Android requires explicit runtime permissions to detect call-end events and query recent call records.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )

            // Primary Action Button when permissions are missing
            if (!allMandatoryGranted) {
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Grant Required Permissions", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Permissions Vertical List
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                permissions.forEach { item ->
                    PermissionRow(item = item)
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(item: PermissionItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Text(
                        text = item.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (item.isMandatory) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFFEE2E2)
                        ) {
                            Text(
                                text = "Required",
                                fontSize = 9.sp,
                                color = RedDanger,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                // Status chip
                val (chipBg, chipFg, chipText) = when (item.status) {
                    PermissionStatus.GRANTED -> Triple(Color(0xFFDCFCE7), Color(0xFF15803D), "GRANTED")
                    PermissionStatus.NOT_GRANTED -> Triple(Color(0xFFFEE2E2), RedDanger, "NOT GRANTED")
                    PermissionStatus.DENIED -> Triple(Color(0xFFFEE2E2), RedDanger, "DENIED")
                    PermissionStatus.RESTRICTED -> Triple(Color(0xFFFEF3C7), Color(0xFFB45309), "RESTRICTED")
                    PermissionStatus.UNAVAILABLE -> Triple(Color(0xFFFFEDD5), Color(0xFFC2410C), "UNAVAILABLE")
                    PermissionStatus.ERROR -> Triple(Color(0xFFFEE2E2), RedDanger, "ERROR")
                    PermissionStatus.NOT_REQUESTED -> Triple(Color(0xFFF1F5F9), TextSecondaryLight, "PENDING")
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = chipBg
                ) {
                    Text(
                        text = chipText,
                        color = chipFg,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = item.rationale,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
        }
    }
}

/**
 * D. Recording Source Selection
 * Presents SAF folder access and MediaStore as a responsive selector.
 * Never squeezes two detailed cards side-by-side on mobile.
 */
@Composable
fun RecordingAccessCard(
    activeMethod: RecordingDiscoveryMethod,
    onSelectMethod: (RecordingDiscoveryMethod) -> Unit,
    safConfigured: Boolean,
    safValid: Boolean,
    safUriString: String?,
    onPickSafFolder: () -> Unit,
    onClearSafFolder: () -> Unit,
    mediaStoreAccessible: Boolean,
    candidateCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Recording Discovery Source",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                text = "Select how Leads365 discovers stock dialer recordings on this phone manufacturer.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )

            // Responsive Method Selector: Stacked or 2-tab segmented control with comfortable touch height
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val isSaf = activeMethod == RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK

                Button(
                    onClick = { onSelectMethod(RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSaf) Navy900 else Color.Transparent,
                        contentColor = if (isSaf) Color.White else MaterialTheme.colorScheme.onSurface
                    ),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Method A: SAF",
                        fontSize = 12.sp,
                        fontWeight = if (isSaf) FontWeight.Bold else FontWeight.Medium
                    )
                }

                Button(
                    onClick = { onSelectMethod(RecordingDiscoveryMethod.MEDIA_STORE) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!isSaf) Navy900 else Color.Transparent,
                        contentColor = if (!isSaf) Color.White else MaterialTheme.colorScheme.onSurface
                    ),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Method B: MediaStore",
                        fontSize = 12.sp,
                        fontWeight = if (!isSaf) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }

            // Method A: Storage Access Framework Details
            if (activeMethod == RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SAF Tree Status",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (safConfigured && safValid) Color(0xFFDCFCE7) else Color(0xFFFEF3C7)
                            ) {
                                Text(
                                    text = if (safConfigured && safValid) "Persisted & Readable" else "Not Configured",
                                    color = if (safConfigured && safValid) Color(0xFF15803D) else Color(0xFFB45309),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = "Recommended for Samsung (/Recordings/Call), Xiaomi (/MIUI/sound_recorder/call_rec), Vivo and Oppo dialers. Persists across reboots.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )

                        if (safUriString != null) {
                            Text(
                                text = "Folder URI: ${PrivacyRedactor.redactFilename(safUriString)}",
                                fontSize = 10.sp,
                                color = TextSecondaryLight,
                                lineHeight = 14.sp
                            )
                        }

                        // Responsive action buttons row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = onPickSafFolder,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Navy800)
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (safConfigured) "Change Folder" else "Select Recordings Folder",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            if (safConfigured) {
                                OutlinedButton(
                                    onClick = onClearSafFolder,
                                    modifier = Modifier.heightIn(min = 44.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Reset", fontSize = 12.sp, color = RedDanger)
                                }
                            }
                        }
                    }
                }
            } else {
                // Method B: MediaStore Details
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "MediaStore Audio Access",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (mediaStoreAccessible) Color(0xFFDCFCE7) else Color(0xFFFEE2E2)
                            ) {
                                Text(
                                    text = if (mediaStoreAccessible) "READY" else "PERMISSION NEEDED",
                                    color = if (mediaStoreAccessible) Color(0xFF15803D) else RedDanger,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = "Queries system audio content provider bounded to call timestamp (± 4 min). Zero folder setup needed if dialer writes to public media store.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFF1F5F9),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Note: Google Phone dialers (Pixel, Motorola) isolate recordings in a private app sandbox and require Cloud Telephony Fallback (Phase 18).",
                                fontSize = 10.sp,
                                color = TextSecondaryLight,
                                modifier = Modifier.padding(8.dp),
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
