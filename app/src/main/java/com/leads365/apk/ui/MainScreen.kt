package com.leads365.apk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.leads365.apk.ui.components.*
import com.leads365.apk.ui.theme.Slate50
import com.leads365.apk.ui.wizard.*
import kotlinx.coroutines.launch

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate50)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            // A. Dark Navy Top Header (Pinned, shrink-0)
            AppHeaderCard(
                deviceModel = uiState.deviceModel,
                androidVersion = uiState.androidVersion
            )

            // B. 4-Step Progress Indicator (Pinned, shrink-0)
            WizardStepBar(
                currentStep = uiState.currentStep,
                onStepClick = { step -> viewModel.setWizardStep(step) }
            )

            // C. Step Content Area (Scrolls independently while Header & Stepper remain pinned)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
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
                            onOpenAdvancedDiagnostics = { viewModel.toggleAdvancedDiagnostics(true) },
                            onNavigateToCalls = onNavigateToCalls
                        )
                    }
                }

                Spacer(modifier = Modifier.height(56.dp))
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

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
