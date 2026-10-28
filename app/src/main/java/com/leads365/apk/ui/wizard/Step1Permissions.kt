package com.leads365.apk.ui.wizard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            // Screen Title & Subtitle (English only)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Permissions & Onboarding Checklist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Navy900,
                    fontSize = 18.sp
                )
                Text(
                    text = "Step 1 of 4 • Phone & Recording Readiness",
                    fontSize = 12.sp,
                    color = BlueAccent,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Leads365 requires phone state and call-log permissions to detect calls and verify dialer recording matches. Audio files are never altered, deleted, or uploaded without your confirmation. No contacts, SMS, location, or microphone permissions are ever requested.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp
                )
            }

            // 1. Truthful Recording Verification Status Banner
            if (isRecordingVerified) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFDCFCE7),
                    border = BorderStroke(1.dp, Color(0xFFBBF7D0)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF15803D), modifier = Modifier.size(22.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Recording Verified",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color(0xFF15803D)
                            )
                            Text(
                                text = "A test call was successfully matched to a real dialer audio recording on this device.",
                                fontSize = 11.sp,
                                color = Color(0xFF166534),
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFFEF3C7),
                    border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFB45309), modifier = Modifier.size(22.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Recording not verified",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color(0xFF92400E)
                            )
                            Text(
                                text = "Connecting a folder or granting permissions does not verify recording capability. Leads365 must complete a test call with a real dialer audio file in Step 4 to confirm compatibility.",
                                fontSize = 11.sp,
                                color = Color(0xFF92400E),
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            // 2. Dialer Automatic Recording Confirmation Checkbox
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFFF8FAFC),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = dialerRecordingConfirmed,
                            onCheckedChange = { onConfirmDialerRecording(it) },
                            colors = CheckboxDefaults.colors(checkedColor = Navy900)
                        )
                        Text(
                            text = "Automatic call recording is enabled in my phone's dialer",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = Navy900
                        )
                    }
                    Text(
                        text = "• For Vivo ($deviceModel): Open the native Phone dialer -> Settings (...) -> Call recording settings -> Record all calls automatically.\n• Leads365 cannot turn on system call recording automatically due to Android platform security boundaries.",
                        fontSize = 11.sp,
                        color = TextSecondaryLight,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }

            // 3. Action-Needed Alert Banner
            if (anyRestrictedOrUnavailable) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFFEF3C7),
                    border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFB45309), modifier = Modifier.size(18.dp))
                            Text(
                                text = "Platform Permission Restriction on $deviceModel",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Color(0xFF92400E)
                            )
                        }
                        Text(
                            text = "Call Log or Phone access is restricted by Android system or privacy settings. Tap 'Open Android Settings' below, select 'Permissions', and ensure 'Call logs' and 'Phone' are allowed.",
                            fontSize = 11.sp,
                            color = Color(0xFF92400E),
                            lineHeight = 15.sp
                        )
                    }
                }
            } else if (mandatoryMissing) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFEFF6FF),
                    border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = BlueAccent, modifier = Modifier.size(18.dp))
                            Text(
                                text = "Action Required",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Navy900
                            )
                        }
                        Text(
                            text = "Required Phone State or Call Log access is missing. Tap 'Grant Required Permissions' below to allow Leads365 to detect calls and verify recording matches.",
                            fontSize = 11.sp,
                            color = Navy900,
                            lineHeight = 15.sp
                        )
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFDCFCE7),
                    border = BorderStroke(1.dp, Color(0xFFBBF7D0)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF15803D), modifier = Modifier.size(20.dp))
                        Text(
                            text = "Required phone permissions are granted! You are ready to proceed.",
                            color = Color(0xFF15803D),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // 4. Checklist Items
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Section: Mandatory Phone Access
                Text(
                    text = "MANDATORY PHONE PERMISSIONS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondaryLight
                )

                // Phone State
                val phoneStateItem = permissions.find { it.id == "perm_phone_state" }
                PermissionChecklistItem(
                    title = "Phone State Access",
                    reason = phoneStateItem?.rationale ?: "Detects when calls connect and disconnect without reading audio streams.",
                    status = phoneStateItem?.status ?: PermissionStatus.NOT_REQUESTED,
                    isMandatory = true,
                    onActionClick = {
                        pendingRationaleTitle = "Phone State Access"
                        pendingRationaleDescription = "Leads365 needs to know when an incoming or outgoing phone call ends so it can look for a corresponding call recording.\n\nStrict Privacy Guarantee: Leads365 never accesses your microphone, audio streams, contacts, or SMS."
                        pendingRationaleAction = onRequestPermissions
                    }
                )

                // Call Log
                val callLogItem = permissions.find { it.id == "perm_call_log" }
                PermissionChecklistItem(
                    title = "Call Log Access",
                    reason = callLogItem?.rationale ?: "Queries call details (timestamp, duration, phone number) to verify against allowed leads.",
                    status = callLogItem?.status ?: PermissionStatus.NOT_REQUESTED,
                    isMandatory = true,
                    onActionClick = {
                        pendingRationaleTitle = "Call Log Access"
                        pendingRationaleDescription = "Leads365 inspects the recent call log to read the call duration, timestamp, and phone number to verify that the call matches a permitted test lead.\n\nStrict Privacy Guarantee: Calls with non-leads or contacts marked Do-Not-Record are ignored and never inspected for recordings."
                        pendingRationaleAction = onRequestPermissions
                    }
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Section: Recording Folder & Storage
                Text(
                    text = "RECORDING FOLDER & STORAGE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondaryLight
                )

                val safItem = permissions.find { it.id == "perm_saf_folder" }
                PermissionChecklistItem(
                    title = "Storage Access Framework (SAF) Folder",
                    reason = safItem?.rationale ?: "Persisted folder access where the phone dialer writes audio files.",
                    status = safItem?.status ?: PermissionStatus.NOT_REQUESTED,
                    isMandatory = false,
                    badgeText = "Configured in Step 2"
                )

                val audioItem = permissions.find { it.id == "perm_audio_access" }
                PermissionChecklistItem(
                    title = "Media Audio Access (MediaStore)",
                    reason = audioItem?.rationale ?: "Optional scanner fallback for dialers that save recordings to public media.",
                    status = audioItem?.status ?: PermissionStatus.NOT_REQUESTED,
                    isMandatory = false,
                    onActionClick = {
                        onRequestPermissions()
                    }
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Section: Optional App Settings
                Text(
                    text = "OPTIONAL SETTINGS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondaryLight
                )

                val callPhoneItem = permissions.find { it.id == "perm_call_phone" }
                PermissionChecklistItem(
                    title = "Direct Calling (Optional)",
                    reason = callPhoneItem?.rationale ?: "Allows one-tap calling from the app. If not granted, calls use the standard phone dialer with ACTION_DIAL.",
                    status = callPhoneItem?.status ?: PermissionStatus.NOT_REQUESTED,
                    isMandatory = false,
                    actionButtonText = if (callPhoneItem?.status != PermissionStatus.GRANTED) "Grant" else null,
                    onActionClick = {
                        pendingRationaleTitle = "Direct Calling Permission"
                        pendingRationaleDescription = "Allows Leads365 to initiate direct test calls to configured leads. If not granted, tapping Call will open your phone dialer instead."
                        pendingRationaleAction = onRequestDirectCallPermission
                    }
                )

                val notifItem = permissions.find { it.id == "perm_notifications" }
                PermissionChecklistItem(
                    title = "Notifications",
                    reason = notifItem?.rationale ?: "Alerts the user when a test call is matched or when background observation completes.",
                    status = notifItem?.status ?: PermissionStatus.NOT_REQUESTED,
                    isMandatory = false,
                    actionButtonText = if (notifItem?.status != PermissionStatus.GRANTED) "Grant" else null,
                    onActionClick = {
                        onRequestPermissions()
                    }
                )

                val overlayItem = permissions.find { it.id == "perm_overlay" }
                PermissionChecklistItem(
                    title = "Caller Pop-up Window (Optional)",
                    reason = overlayItem?.rationale ?: "Allows display of lead identification badges over incoming or outgoing calls.",
                    status = overlayItem?.status ?: PermissionStatus.NOT_REQUESTED,
                    isMandatory = false,
                    actionButtonText = if (overlayItem?.status != PermissionStatus.GRANTED) "Configure" else null,
                    onActionClick = onRequestOverlayPermission
                )

                val batteryOptItem = permissions.find { it.id == "perm_battery_opt" }
                PermissionChecklistItem(
                    title = "Background Execution / Battery (Optional)",
                    reason = batteryOptItem?.rationale ?: "Prevents Android from stopping background call observation and delayed recording discovery.",
                    status = batteryOptItem?.status ?: PermissionStatus.NOT_REQUESTED,
                    isMandatory = false,
                    actionButtonText = if (batteryOptItem?.status != PermissionStatus.GRANTED) "Configure" else null,
                    onActionClick = onRequestBatteryOptimization
                )
            }

            // 5. Action Buttons & Navigation Controls
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canContinue) {
                    // All mandatory permissions satisfied: Primary Continue button
                    Button(
                        onClick = onContinue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Navy900)
                    ) {
                        Text(
                            text = "Continue to Step 2 (Select Folder) →",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                } else {
                    if (!mandatoryMissing && !dialerRecordingConfirmed) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFFEF3C7),
                            border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFB45309), modifier = Modifier.size(18.dp))
                                Text(
                                    text = "All required permissions are granted! Please check the box above confirming native call recording is enabled in your phone dialer to continue.",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF92400E)
                                )
                            }
                        }
                    } else if (anyRestrictedOrUnavailable) {
                        Button(
                            onClick = onOpenSettings,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open Android Settings", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    pendingRationaleTitle = "Required Phone Permissions"
                                    pendingRationaleDescription = "Leads365 requires Phone State and Call Log access to observe when test calls finish and identify matches.\n\nStrict Privacy Guarantee: Strictly NO microphone recording, contacts access, or SMS inspection."
                                    pendingRationaleAction = onRequestPermissions
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Retry Dialog", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }

                            OutlinedButton(
                                onClick = onCheckAgain,
                                modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Check Again", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                pendingRationaleTitle = "Required Phone Permissions"
                                pendingRationaleDescription = "Leads365 requires Phone State and Call Log access to observe when test calls finish and identify matches.\n\nStrict Privacy Guarantee: Strictly NO microphone recording, contacts access, or SMS inspection."
                                pendingRationaleAction = onRequestPermissions
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Grant Required Permissions", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Open Settings", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }

                            OutlinedButton(
                                onClick = onCheckAgain,
                                modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Check Again", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    // Disabled Continue Button with Clear Explanation
                    Button(
                        onClick = { /* Disabled */ },
                        enabled = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = Color(0xFFE2E8F0),
                            disabledContentColor = Color(0xFF94A3B8)
                        )
                    ) {
                        Text(
                            text = "Grant Phone State and Call Log access to continue",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
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
                Icon(Icons.Default.Security, contentDescription = null, tint = BlueAccent)
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
                    colors = ButtonDefaults.buttonColors(containerColor = Navy900)
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
private fun PermissionChecklistItem(
    title: String,
    reason: String,
    status: PermissionStatus,
    isMandatory: Boolean,
    badgeText: String? = null,
    actionButtonText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
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
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isMandatory) {
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
                    } else if (badgeText != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFF1F5F9)
                        ) {
                            Text(
                                text = badgeText,
                                fontSize = 9.sp,
                                color = TextSecondaryLight,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val (chipBg, chipFg, chipText) = when (status) {
                        PermissionStatus.GRANTED -> Triple(Color(0xFFDCFCE7), Color(0xFF15803D), "Granted")
                        PermissionStatus.NOT_GRANTED -> Triple(Color(0xFFFEE2E2), RedDanger, "Not Granted")
                        PermissionStatus.DENIED -> Triple(Color(0xFFFEE2E2), RedDanger, "Denied")
                        PermissionStatus.RESTRICTED -> Triple(Color(0xFFFEF3C7), Color(0xFFB45309), "Restricted")
                        PermissionStatus.UNAVAILABLE -> Triple(Color(0xFFFFEDD5), Color(0xFFC2410C), "Unavailable")
                        PermissionStatus.ERROR -> Triple(Color(0xFFFEE2E2), RedDanger, "Error")
                        PermissionStatus.NOT_REQUESTED -> Triple(Color(0xFFF1F5F9), TextSecondaryLight, "Pending")
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = chipBg
                    ) {
                        Text(
                            text = chipText,
                            color = chipFg,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (actionButtonText != null && onActionClick != null) {
                        OutlinedButton(
                            onClick = onActionClick,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(text = actionButtonText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Text(
                text = reason,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
        }
    }
}
