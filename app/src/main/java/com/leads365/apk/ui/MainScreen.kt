package com.leads365.apk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leads365.apk.ui.components.*
import com.leads365.apk.ui.theme.BlueAccent
import com.leads365.apk.ui.wizard.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestPermissions: () -> Unit,
    onRequestDirectCallPermission: () -> Unit = {},
    onRequestOverlayPermission: () -> Unit = {},
    onRequestBatteryOptimization: () -> Unit = {},
    onOpenAppSettings: () -> Unit,
    onPickSafFolder: () -> Unit,
    onNavigateToCalls: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // A. Compact Top Header (Brand, short title, concise explanation, device/OS chips)
            AppHeaderCard(
                deviceModel = uiState.deviceModel,
                androidVersion = uiState.androidVersion
            )

            // B. 4-Step Progress Indicator
            WizardStepBar(
                currentStep = uiState.currentStep,
                onStepClick = { step -> viewModel.setWizardStep(step) }
            )

            // C. Step-by-Step Guided Wizard Content
            when (uiState.currentStep) {
                WizardStep.PERMISSIONS -> {
                    Step1Permissions(
                        permissions = uiState.permissions,
                        canContinue = uiState.canContinueToStep2,
                        deviceModel = uiState.deviceModel,
                        androidVersion = uiState.androidVersion,
                        dialerRecordingConfirmed = uiState.dialerRecordingConfirmed,
                        isRecordingVerified = uiState.isRecordingVerified,
                        onConfirmDialerRecording = { viewModel.setDialerRecordingConfirmed(it) },
                        onRequestPermissions = onRequestPermissions,
                        onRequestDirectCallPermission = onRequestDirectCallPermission,
                        onRequestOverlayPermission = onRequestOverlayPermission,
                        onRequestBatteryOptimization = onRequestBatteryOptimization,
                        onOpenSettings = onOpenAppSettings,
                        onCheckAgain = { viewModel.refreshState() },
                        onContinue = { viewModel.nextStep() }
                    )
                }
                WizardStep.RECORDING_FOLDER -> {
                    Step2Folder(
                        safConfigured = uiState.safConfigured,
                        safValid = uiState.safValid,
                        safUriString = uiState.safUriString,
                        safInspection = uiState.safInspection,
                        mediaStoreAccessible = uiState.mediaStoreAccessible,
                        activeMethod = uiState.activeDiscoveryMethod,
                        folderQuerySuccess = uiState.folderQuerySuccess,
                        folderQueryMessage = uiState.folderQueryMessage,
                        canContinue = uiState.canContinueToStep3,
                        deviceModel = uiState.deviceModel,
                        onPickFolder = onPickSafFolder,
                        onClearFolder = { viewModel.clearSafTreeUri() },
                        onVerifyFolder = { viewModel.verifyFolderAccess() },
                        onSelectMethod = { viewModel.setActiveDiscoveryMethod(it) },
                        onBack = { viewModel.previousStep() },
                        onContinue = { viewModel.nextStep() }
                    )
                }
                WizardStep.TEST_CALL -> {
                    Step3TestCall(
                        testLeads = uiState.testLeads,
                        selectedLeadId = uiState.selectedLeadId,
                        recentCallEvent = uiState.recentCallEvent,
                        gateResult = uiState.gateResult,
                        callMonitoringState = uiState.callMonitoringState,
                        callLogAccessStatus = uiState.callLogAccessStatus,
                        callLogErrorMessage = uiState.callLogErrorMessage,
                        isCallSimulated = uiState.isCallSimulated,
                        isSearchingRecording = uiState.isSearchingRecording,
                        canContinue = uiState.canContinueToStep4,
                        deviceModel = uiState.deviceModel,
                        onAddLead = { name, number, dnr -> viewModel.addTestLead(name, number, dnr) },
                        onSelectLead = { id -> viewModel.selectLead(id) },
                        onRemoveLead = { id -> viewModel.removeTestLead(id) },
                        onToggleDnr = { id, dnr -> viewModel.toggleDnr(id, dnr) },
                        onCheckRecentCall = { viewModel.checkForRecentCall() },
                        onSimulateCall = { viewModel.simulateCall(it) },
                        onBack = { viewModel.previousStep() },
                        onContinue = { viewModel.nextStep() }
                    )
                }
                WizardStep.TEST_RESULT -> {
                    Step4TestResult(
                        recentCallEvent = uiState.recentCallEvent,
                        gateResult = uiState.gateResult,
                        matchingResult = uiState.matchingResult,
                        candidateRecordings = uiState.candidateRecordings,
                        isCallSimulated = uiState.isCallSimulated,
                        isSearchingRecording = uiState.isSearchingRecording,
                        recordingDiscoveryState = uiState.recordingDiscoveryState,
                        uploadState = uiState.uploadState,
                        deviceModel = uiState.deviceModel,
                        onStartNewTest = { viewModel.resetTestState() },
                        onRequestUpload = { viewModel.requestUploadConfirmation(it) },
                        onRetryUpload = { viewModel.executeConfirmedUpload() },
                        onOpenAdvancedDiagnostics = { viewModel.toggleAdvancedDiagnostics(true) }
                    )
                }
            }

            if (uiState.currentStep == WizardStep.TEST_RESULT) {
                Button(
                    onClick = onNavigateToCalls,
                    colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Go to Call History & Recordings →", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Upload Confirmation Modal Dialog
        if (uiState.showUploadConfirmation && uiState.selectedCandidateForUpload != null) {
            UploadConfirmationDialog(
                candidate = uiState.selectedCandidateForUpload!!,
                checksum = uiState.selectedCandidateChecksum,
                endpointUrl = uiState.uploadEndpointUrl,
                onConfirm = { viewModel.executeConfirmedUpload() },
                onDismiss = { viewModel.dismissUploadConfirmation() }
            )
        }

        // Advanced Diagnostics Bottom Sheet
        if (uiState.showAdvancedDiagnostics) {
            AdvancedDiagnosticsSheet(
                endpointUrl = uiState.uploadEndpointUrl,
                onEndpointChanged = { viewModel.setUploadEndpointUrl(it) },
                auditLogs = uiState.auditLogs,
                onCopyDiagnostics = {
                    val json = viewModel.exportRedactedDiagnostics()
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Leads365 Diagnostics", json))
                    scope.launch {
                        snackbarHostState.showSnackbar("Redacted diagnostics copied to clipboard")
                    }
                },
                onDismiss = { viewModel.toggleAdvancedDiagnostics(false) }
            )
        }
    }
}
