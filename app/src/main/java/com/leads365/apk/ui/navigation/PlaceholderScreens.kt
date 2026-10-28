package com.leads365.apk.ui.navigation
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PrivacyTip
import com.leads365.apk.BuildConfig
import com.leads365.apk.diagnostics.BackgroundHardeningHelper
import com.leads365.apk.ui.theme.*


@Composable
fun SettingsScreen(
    onRequestPermissions: () -> Unit = {},
    onRequestOverlayPermission: () -> Unit = {},
    onRequestBatteryOptimization: (() -> Unit)? = null,
    onOpenAppSettings: () -> Unit = {},
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToDeviceSetup: () -> Unit = {},
    onNavigateToAccount: () -> Unit = {},
    authViewModel: com.leads365.apk.ui.auth.AuthViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    diagnosticsViewModel: com.leads365.apk.diagnostics.DiagnosticsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var hasOverlayPermission by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
        )
    }
    var isBatteryOptimizationIgnored by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            BackgroundHardeningHelper.isBatteryOptimizationIgnored(context)
        )
    }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(context)
                } else {
                    true
                }
                isBatteryOptimizationIgnored = BackgroundHardeningHelper.isBatteryOptimizationIgnored(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val testResult by diagnosticsViewModel.testResult.collectAsState()
    val isRunning by diagnosticsViewModel.isRunning.collectAsState()
    val authState by authViewModel.authState.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Slate50
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        Text(
            text = "App Settings & Environment",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Navy900
        )

        // Agent Account & Session Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = LightSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PrivacyTip,
                            contentDescription = null,
                            tint = BlueAccent,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            val name = when (val state = authState) {
                                is com.leads365.apk.data.repository.AuthState.Authenticated -> state.user.name
                                else -> "Agent (Local Test Mode)"
                            }
                            val email = when (val state = authState) {
                                is com.leads365.apk.data.repository.AuthState.Authenticated -> state.user.email
                                else -> "Dev Sandbox Mode"
                            }
                            Text(
                                text = name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Navy900
                            )
                            Text(
                                text = email,
                                fontSize = 11.sp,
                                color = TextSecondaryLight
                            )
                        }
                    }

                    if (authState is com.leads365.apk.data.repository.AuthState.Authenticated) {
                        Surface(
                            color = TealSuccess.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "LOGGED IN",
                                color = TealSuccess,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        Surface(
                            color = BlueAccent.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "LOCAL DEV",
                                color = BlueAccent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onNavigateToAccount,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 34.dp)
                    ) {
                        Text("Account Details", fontSize = 11.sp)
                    }

                    if (authState is com.leads365.apk.data.repository.AuthState.Authenticated) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = { authViewModel.logout() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(min = 34.dp)
                        ) {
                            Text("Sign Out", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
            }
        }

        // Native Dialer Recording Setup & Compatibility Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = LightSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Native Dialer Recording Setup",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Navy900
                        )
                        Text(
                            text = "Configure recording folder access & verify hardware call matching",
                            fontSize = 11.sp,
                            color = TextSecondaryLight
                        )
                    }

                    Button(
                        onClick = onNavigateToDeviceSetup,
                        colors = ButtonDefaults.buttonColors(containerColor = Navy900),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Open Setup", fontSize = 11.sp)
                    }
                }
            }
        }

        // Phase 18: Telephony Call Mode (Native Dialer vs Cloud Telephony Fallback)
        val telephonyPrefs = context.getSharedPreferences(com.leads365.apk.telephony.CloudTelephonyManager.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        var selectedCallMode by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(
                telephonyPrefs.getString(
                    com.leads365.apk.telephony.CloudTelephonyManager.KEY_CALL_MODE,
                    com.leads365.apk.telephony.TelephonyCallMode.NATIVE_DIALER.name
                ) ?: com.leads365.apk.telephony.TelephonyCallMode.NATIVE_DIALER.name
            )
        }
        var agentPhone by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(
                telephonyPrefs.getString(com.leads365.apk.telephony.CloudTelephonyManager.KEY_AGENT_PHONE, "") ?: ""
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Slate200),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Call Routing & Telephony Mode",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            color = Slate900
                        )
                        Text(
                            text = "Choose between local device dialer and cloud bridge fallback",
                            fontSize = 11.sp,
                            color = Slate500,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = if (selectedCallMode == com.leads365.apk.telephony.TelephonyCallMode.CLOUD_TELEPHONY.name) Blue50 else Slate100,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (selectedCallMode == com.leads365.apk.telephony.TelephonyCallMode.CLOUD_TELEPHONY.name) "CLOUD" else "NATIVE",
                            color = if (selectedCallMode == com.leads365.apk.telephony.TelephonyCallMode.CLOUD_TELEPHONY.name) Blue600 else Slate900,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                val isNative = selectedCallMode == com.leads365.apk.telephony.TelephonyCallMode.NATIVE_DIALER.name
                val isCloud = selectedCallMode == com.leads365.apk.telephony.TelephonyCallMode.CLOUD_TELEPHONY.name
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            selectedCallMode = com.leads365.apk.telephony.TelephonyCallMode.NATIVE_DIALER.name
                            telephonyPrefs.edit().putString(
                                com.leads365.apk.telephony.CloudTelephonyManager.KEY_CALL_MODE,
                                com.leads365.apk.telephony.TelephonyCallMode.NATIVE_DIALER.name
                            ).apply()
                        },
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isNative) Blue600 else Slate100,
                            contentColor = if (isNative) Color.White else Slate700
                        )
                    ) {
                        Text("Native Dialer", fontSize = 11.sp, fontWeight = if (isNative) FontWeight.Bold else FontWeight.Medium)
                    }

                    Button(
                        onClick = {
                            selectedCallMode = com.leads365.apk.telephony.TelephonyCallMode.CLOUD_TELEPHONY.name
                            telephonyPrefs.edit().putString(
                                com.leads365.apk.telephony.CloudTelephonyManager.KEY_CALL_MODE,
                                com.leads365.apk.telephony.TelephonyCallMode.CLOUD_TELEPHONY.name
                            ).apply()
                        },
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCloud) Blue600 else Slate100,
                            contentColor = if (isCloud) Color.White else Slate700
                        )
                    ) {
                        Text("Cloud Telephony", fontSize = 11.sp, fontWeight = if (isCloud) FontWeight.Bold else FontWeight.Medium)
                    }
                }

                if (selectedCallMode == com.leads365.apk.telephony.TelephonyCallMode.CLOUD_TELEPHONY.name) {
                    Text(
                        text = "Cloud Telephony bridges your phone to the lead via telecom provider (Exotel/Twilio). Call is recorded at the switch. Enter the phone number you want the service to call first:",
                        fontSize = 11.sp,
                        color = Slate500
                    )
                    OutlinedTextField(
                        value = agentPhone,
                        onValueChange = {
                            agentPhone = it
                            telephonyPrefs.edit().putString(
                                com.leads365.apk.telephony.CloudTelephonyManager.KEY_AGENT_PHONE,
                                it.trim()
                            ).apply()
                        },
                        label = { Text("Your Phone Number (e.g. +919876543210)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                } else {
                    Text(
                        text = "Native Dialer mode places calls directly using your SIM card. Audio recordings are discovered locally from device storage via SAF/MediaStore.",
                        fontSize = 11.sp,
                        color = Slate500
                    )
                }
            }
        }

        // Phase 11: Diagnostics & Self-Test Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Slate200),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Diagnostics & Self-Test",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            color = Slate900
                        )
                        Text(
                            text = "Operational readiness & prerequisite audit",
                            fontSize = 11.sp,
                            color = Slate500,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { diagnosticsViewModel.runSelfTest() },
                        enabled = !isRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = Blue600),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Testing...", fontSize = 11.sp, maxLines = 1, softWrap = false)
                        } else {
                            Text("Run Self-Test", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                    }
                }

                // Summary Badges
                val report = testResult
                if (report != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DiagnosticSummaryBadge(
                            label = "${report.passedCount} PASS",
                            color = Emerald600,
                            modifier = Modifier.weight(1f)
                        )
                        DiagnosticSummaryBadge(
                            label = "${report.warningCount} WARN",
                            color = Amber600,
                            modifier = Modifier.weight(1f)
                        )
                        DiagnosticSummaryBadge(
                            label = "${report.failedCount} FAIL",
                            color = Red600,
                            modifier = Modifier.weight(1f)
                        )
                        if (report.untestedCount > 0) {
                            DiagnosticSummaryBadge(
                                label = "${report.untestedCount} UNTESTED",
                                color = Slate500,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Diagnostic Items List
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        report.items.forEach { item ->
                            DiagnosticItemRow(
                                item = item,
                                onRequestPermissions = onRequestPermissions,
                                onOpenAppSettings = onOpenAppSettings
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Diagnostics have not yet run. Tap 'Run Self-Test' to evaluate device readiness.",
                        fontSize = 12.sp,
                        color = TextSecondaryLight
                    )
                }
            }
        }

        // Phase 10: Caller Identification Pop-up & Overlay Permission
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = LightSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Caller Identification Card",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Navy900
                    )
                    Surface(
                        color = if (hasOverlayPermission) TealSuccess.copy(alpha = 0.15f) else BlueAccent.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (hasOverlayPermission) "OVERLAY ACTIVE" else "HEADS-UP FALLBACK",
                            color = if (hasOverlayPermission) TealSuccess else BlueAccent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = "Displays lead name, status, and last note when calling or receiving a call from an assigned lead. When overlay permission is disabled, Leads365 uses a high-priority heads-up notification instead.",
                    color = TextSecondaryLight,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                Button(
                    onClick = onRequestOverlayPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = if (hasOverlayPermission) "Manage Overlay Permission" else "Enable Overlay Pop-up",
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Phase 12: Phone-Maker Background Execution & Battery Optimization Card
        val assessment = BackgroundHardeningHelper.evaluateBackgroundHardening(context)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Slate200),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Background Execution & Battery",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp,
                        color = Slate900,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = if (isBatteryOptimizationIgnored) Emerald50 else Amber50,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, if (isBatteryOptimizationIgnored) Emerald100 else Amber100)
                    ) {
                        Text(
                            text = if (isBatteryOptimizationIgnored) "OPTIMIZATION EXEMPT" else "OPTIMIZATION ACTIVE",
                            color = if (isBatteryOptimizationIgnored) Emerald600 else Amber600,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = "Device: ${assessment.deviceModel}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = Slate900
                )

                Text(
                    text = assessment.actionableGuidance,
                    color = TextSecondaryLight,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                Surface(
                    color = BlueAccent.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Verified status: ${assessment.verifiedByApp}",
                        fontSize = 11.sp,
                        color = Navy900,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            if (onRequestBatteryOptimization != null) {
                                onRequestBatteryOptimization()
                            } else {
                                BackgroundHardeningHelper.launchSafeSystemSettings(context)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isBatteryOptimizationIgnored) Navy900 else Color(0xFFD97706)
                        )
                    ) {
                        Text(
                            text = if (isBatteryOptimizationIgnored) "Manage Battery Settings" else "Disable Optimization",
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // Phase 9: Privacy Controls & What Was Uploaded
        Card(
            onClick = onNavigateToPrivacy,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = LightSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.PrivacyTip,
                        contentDescription = null,
                        tint = BlueAccent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Privacy Controls & DNR",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Navy900
                        )
                        Text(
                            text = "Agency policy, DNR status & confirmed uploads",
                            fontSize = 12.sp,
                            color = TextSecondaryLight
                        )
                    }
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Navigate to Privacy",
                    tint = TextTertiaryLight,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = LightSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Build Information",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Navy900
                )

                EnvironmentRow(label = "Flavour", value = BuildConfig.FLAVOR.uppercase())
                EnvironmentRow(label = "Base URL", value = BuildConfig.BASE_URL)
                EnvironmentRow(label = "Version Name", value = BuildConfig.VERSION_NAME)
                EnvironmentRow(label = "Version Code", value = BuildConfig.VERSION_CODE.toString())
                EnvironmentRow(label = "Build Type", value = BuildConfig.BUILD_TYPE)
            }
        }
    }
}
}

@Composable
private fun DiagnosticSummaryBadge(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
        modifier = modifier
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(vertical = 6.dp)
                .fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun DiagnosticItemRow(
    item: com.leads365.apk.diagnostics.DiagnosticItem,
    onRequestPermissions: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {}
) {
    val statusColor = when (item.status) {
        com.leads365.apk.diagnostics.DiagnosticStatus.PASS -> Emerald600
        com.leads365.apk.diagnostics.DiagnosticStatus.WARNING -> Amber600
        com.leads365.apk.diagnostics.DiagnosticStatus.FAIL -> Red600
        com.leads365.apk.diagnostics.DiagnosticStatus.NOT_TESTED -> Slate500
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Slate50,
        border = BorderStroke(1.dp, Slate200.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = Slate900,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = statusColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = item.status.name,
                        color = statusColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = item.summary,
                fontSize = 11.sp,
                color = Slate500,
                lineHeight = 15.sp
            )

            if (!item.actionableStep.isNullOrBlank()) {
                Surface(
                    color = Color(0xFFF59E0B).copy(alpha = 0.08f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Next Step: ${item.actionableStep}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFD97706)
                        )
                        if (item.id == "diag_battery_opt") {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            Button(
                                onClick = {
                                    com.leads365.apk.diagnostics.BackgroundHardeningHelper.launchSafeSystemSettings(context)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Open Battery Settings", fontSize = 10.sp, color = Color.White)
                            }
                        } else if (item.id == "diag_call_detection" || item.id == "diag_telephony") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = onRequestPermissions,
                                    colors = ButtonDefaults.buttonColors(containerColor = Navy900),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.heightIn(min = 32.dp)
                                ) {
                                    Text("Grant Permissions", fontSize = 11.sp, color = Color.White)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                OutlinedButton(
                                    onClick = onOpenAppSettings,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.heightIn(min = 32.dp)
                                ) {
                                    Text("App Settings", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextSecondaryLight
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Navy900
        )
    }
}
