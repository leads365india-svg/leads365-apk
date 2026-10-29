package com.leads365.apk.ui.wizard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leads365.apk.core.PermissionItem
import com.leads365.apk.core.PermissionStatus
import com.leads365.apk.ui.theme.*

@Composable
fun Step1Permissions(
    permissions: List<PermissionItem>,
    canContinue: Boolean,
    deviceModel: String,
    androidVersion: String,
    dialerRecordingConfirmed: Boolean = false,
    isRecordingVerified: Boolean = false,
    onConfirmDialerRecording: (Boolean) -> Unit = {},
    onRequestPermissions: () -> Unit,
    onRequestDirectCallPermission: () -> Unit = {},
    onRequestOverlayPermission: () -> Unit = {},
    onRequestBatteryOptimization: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onCheckAgain: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mandatoryMissing = permissions.any { it.isMandatory && it.status != PermissionStatus.GRANTED }
    val anyRestrictedOrUnavailable = permissions.any {
        it.isMandatory && (it.status == PermissionStatus.RESTRICTED ||
                it.status == PermissionStatus.UNAVAILABLE ||
                it.status == PermissionStatus.ERROR)
    }

    var pendingRationaleTitle by remember { mutableStateOf<String?>(null) }
    var pendingRationaleDescription by remember { mutableStateOf<String?>(null) }
    var pendingRationaleAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section Heading
        Text(
            text = "Permissions & Onboarding Checklist",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Slate900
        )

        // Compact Amber Warning Card when recording capability has not yet been verified
        if (isRecordingVerified) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Emerald50,
                border = BorderStroke(1.dp, Emerald200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Emerald600,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Recording Verified",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Emerald800
                        )
                        Text(
                            text = "Audio recording capability verified with genuine dialer audio in Step 4.",
                            fontSize = 11.sp,
                            color = Emerald700
                        )
                    }
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Amber50,
                border = BorderStroke(1.dp, Amber200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Amber600),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "i",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Recording not verified",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Amber900
                        )
                        Text(
                            text = "Connecting a folder or granting permissions does not verify recording capability. Leads365 must complete a test call with a real dialer audio file in Step 4.",
                            fontSize = 11.sp,
                            color = Amber800,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        // White Bordered Card containing permission / state rows
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Row 1: Automatic call recording enabled
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConfirmDialerRecording(!dialerRecordingConfirmed) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Checkbox(
                            checked = dialerRecordingConfirmed,
                            onCheckedChange = { onConfirmDialerRecording(it) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Emerald600,
                                uncheckedColor = Slate400
                            ),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "Automatic call recording enabled",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate900
                        )
                    }
                    StatusPill(
                        statusText = if (dialerRecordingConfirmed) "Granted" else "Required",
                        isGranted = dialerRecordingConfirmed
                    )
                }

                HorizontalDivider(thickness = 1.dp, color = Slate200)

                // Row 2: Phone State Access
                val phoneStateItem = permissions.find { it.id == "perm_phone_state" }
                val phoneStateGranted = phoneStateItem?.status == PermissionStatus.GRANTED
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!phoneStateGranted) {
                                pendingRationaleTitle = "Phone State Access"
                                pendingRationaleDescription = "Leads365 needs to know when an incoming or outgoing phone call ends so it can look for a corresponding call recording.\n\nStrict Privacy Guarantee: Leads365 never accesses your microphone, audio streams, contacts, or SMS."
                                pendingRationaleAction = onRequestPermissions
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Phone State Access",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate900
                    )
                    StatusPill(
                        statusText = when (phoneStateItem?.status) {
                            PermissionStatus.GRANTED -> "Granted"
                            PermissionStatus.RESTRICTED -> "Restricted"
                            PermissionStatus.DENIED -> "Denied"
                            else -> "Required"
                        },
                        isGranted = phoneStateGranted
                    )
                }

                HorizontalDivider(thickness = 1.dp, color = Slate200)

                // Row 3: Call Log Access
                val callLogItem = permissions.find { it.id == "perm_call_log" }
                val callLogGranted = callLogItem?.status == PermissionStatus.GRANTED
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!callLogGranted) {
                                pendingRationaleTitle = "Call Log Access"
                                pendingRationaleDescription = "Leads365 inspects the recent call log to read the call duration, timestamp, and phone number to verify that the call matches a permitted test lead.\n\nStrict Privacy Guarantee: Calls with non-leads or contacts marked Do-Not-Record are ignored and never inspected for recordings."
                                pendingRationaleAction = onRequestPermissions
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Call Log Access",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate900
                    )
                    StatusPill(
                        statusText = when (callLogItem?.status) {
                            PermissionStatus.GRANTED -> "Granted"
                            PermissionStatus.RESTRICTED -> "Restricted"
                            PermissionStatus.DENIED -> "Denied"
                            else -> "Required"
                        },
                        isGranted = callLogGranted
                    )
                }

                HorizontalDivider(thickness = 1.dp, color = Slate200)

                // Row 4: Storage Access Framework (SAF)
                val safItem = permissions.find { it.id == "perm_saf_folder" }
                val safGranted = safItem?.status == PermissionStatus.GRANTED
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Storage Access Framework (SAF)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate900
                    )
                    StatusPill(
                        statusText = if (safGranted) "Granted" else "Step 2",
                        isGranted = safGranted
                    )
                }
            }
        }

        // Action Buttons if required permissions need granting or retrying
        if (anyRestrictedOrUnavailable) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Amber50,
                border = BorderStroke(1.dp, Amber200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Permission restricted by system settings on $deviceModel. Tap below to enable Call Logs and Phone access in Android Settings.",
                        fontSize = 11.sp,
                        color = Amber800,
                        lineHeight = 15.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOpenSettings,
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Slate900)
                        ) {
                            Text("Open Settings", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onCheckAgain,
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Check Again", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else if (mandatoryMissing) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Blue50,
                border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Required Phone State or Call Log access is missing. Tap below to grant access.",
                        fontSize = 11.sp,
                        color = Slate800,
                        lineHeight = 15.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                pendingRationaleTitle = "Required Phone Permissions"
                                pendingRationaleDescription = "Leads365 requires Phone State and Call Log access to observe when test calls finish and identify matches.\n\nStrict Privacy Guarantee: Strictly NO microphone recording, contacts access, or SMS inspection."
                                pendingRationaleAction = onRequestPermissions
                            },
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Slate900)
                        ) {
                            Text("Grant Permissions", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onCheckAgain,
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Check Again", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else if (!dialerRecordingConfirmed) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Amber50,
                border = BorderStroke(1.dp, Amber200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Amber700,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Please check 'Automatic call recording enabled' above to proceed.",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Amber900
                    )
                }
            }
        }

        // Continue Button: Full-width Emerald Button
        Button(
            onClick = onContinue,
            enabled = canContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Emerald800,
                disabledContainerColor = Slate200,
                disabledContentColor = Slate500
            )
        ) {
            Text(
                text = "Continue to Step 2 (Select Folder) →",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (canContinue) Color.White else Slate500
            )
        }
    }

    // Pre-Permission Rationale Explanation Dialog
    if (pendingRationaleTitle != null && pendingRationaleDescription != null) {
        AlertDialog(
            onDismissRequest = {
                pendingRationaleTitle = null
                pendingRationaleDescription = null
                pendingRationaleAction = null
            },
            icon = {
                Icon(Icons.Default.Security, contentDescription = null, tint = Blue600)
            },
            title = {
                Text(
                    text = pendingRationaleTitle ?: "Permission Rationale",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = pendingRationaleDescription ?: "",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val action = pendingRationaleAction
                        pendingRationaleTitle = null
                        pendingRationaleDescription = null
                        pendingRationaleAction = null
                        action?.invoke()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Slate900)
                ) {
                    Text("Proceed to Android Prompt")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingRationaleTitle = null
                        pendingRationaleDescription = null
                        pendingRationaleAction = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun StatusPill(
    statusText: String,
    isGranted: Boolean,
    modifier: Modifier = Modifier
) {
    val bg = if (isGranted) Color(0xFFDCFCE7) else Color(0xFFFEF3C7)
    val fg = if (isGranted) Color(0xFF15803D) else Color(0xFFB45309)

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bg,
        modifier = modifier
    ) {
        Text(
            text = statusText,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
