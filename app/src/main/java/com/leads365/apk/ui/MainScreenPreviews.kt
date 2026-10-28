package com.leads365.apk.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.leads365.apk.core.*
import com.leads365.apk.ui.components.AppHeaderCard
import com.leads365.apk.ui.theme.Leads365Theme
import com.leads365.apk.ui.wizard.*

@Composable
fun PreviewWizardStep(step: WizardStep) {
    val sampleLeads = listOf(
        TestLead("1", "Acme Realty Test Lead", "+91 98765 43210", "+919876543210", isDoNotRecord = false),
        TestLead("2", "VIP Partner (DNR)", "+91 98765 43212", "+919876543212", isDoNotRecord = true)
    )

    val samplePermissions = listOf(
        PermissionItem(
            id = "perm_phone",
            title = "Phone State Access",
            rationale = "Detects when calls end to initiate recording lookup.",
            status = PermissionStatus.GRANTED,
            isMandatory = true
        ),
        PermissionItem(
            id = "perm_call_log",
            title = "Call Log Access",
            rationale = "Reads call duration, timestamp, and direction.",
            status = PermissionStatus.NOT_REQUESTED,
            isMandatory = true
        ),
        PermissionItem(
            id = "perm_audio",
            title = "Dialer Recording Access",
            rationale = "Locates recording without modifying original file.",
            status = PermissionStatus.GRANTED,
            isMandatory = false
        )
    )

    val sampleCandidate = RecordingCandidate(
        uriString = "content://recordings/123",
        displayName = "Call_to_+919876543210_261024_120000.m4a",
        sizeBytes = 245_760L,
        lastModifiedEpochMs = System.currentTimeMillis() - 10_000L,
        mimeType = "audio/m4a",
        durationSeconds = 30L,
        sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
    )

    val sampleCall = CallEvent(
        id = "call_prev_1",
        rawNumber = "+91 98765 43210",
        normalizedNumber = "+919876543210",
        startTimeEpochMs = System.currentTimeMillis() - 40_000L,
        endTimeEpochMs = System.currentTimeMillis() - 10_000L,
        durationSeconds = 30L,
        direction = CallDirection.OUTGOING
    )

    val sampleMatch = RecordingMatchResult(
        status = MatchStatus.FILE_FOUND,
        matchedCandidate = sampleCandidate,
        candidateCount = 1,
        confidenceScore = 0.95,
        reason = "Unique match confirmed with confidence 95%."
    )

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppHeaderCard(
                deviceModel = "Vivo V2111",
                androidVersion = "Android 12 (API 31)"
            )

            WizardStepBar(
                currentStep = step,
                onStepClick = {}
            )

            when (step) {
                WizardStep.PERMISSIONS -> {
                    Step1Permissions(
                        permissions = samplePermissions,
                        canContinue = false,
                        deviceModel = "Vivo V2111",
                        androidVersion = "Android 12",
                        onRequestPermissions = {},
                        onOpenSettings = {},
                        onCheckAgain = {},
                        onContinue = {}
                    )
                }
                WizardStep.RECORDING_FOLDER -> {
                    Step2Folder(
                        safConfigured = true,
                        safValid = true,
                        safUriString = "content://com.android.externalstorage.documents/tree/primary%3ARecordings%2FCall",
                        safInspection = null,
                        mediaStoreAccessible = true,
                        activeMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK,
                        folderQuerySuccess = true,
                        folderQueryMessage = "Folder verified and readable. Found 4 audio file(s).",
                        canContinue = true,
                        deviceModel = "Vivo V2111",
                        onPickFolder = {},
                        onClearFolder = {},
                        onVerifyFolder = {},
                        onSelectMethod = {},
                        onBack = {},
                        onContinue = {}
                    )
                }
                WizardStep.TEST_CALL -> {
                    Step3TestCall(
                        testLeads = sampleLeads,
                        selectedLeadId = sampleLeads.first().id,
                        recentCallEvent = sampleCall,
                        gateResult = GateEvaluationResult.Allowed(sampleLeads.first(), sampleCall),
                        callMonitoringState = com.leads365.apk.calls.CallMonitoringState.IdleWaiting,
                        callLogAccessStatus = com.leads365.apk.calls.CallLogAccessStatus.ACCESSIBLE,
                        callLogErrorMessage = null,
                        isCallSimulated = false,
                        isSearchingRecording = false,
                        canContinue = true,
                        deviceModel = "Vivo V2111",
                        onAddLead = { name, number, dnr -> Result.success(sampleLeads.first()) },
                        onSelectLead = {},
                        onRemoveLead = {},
                        onToggleDnr = { _, _ -> },
                        onCheckRecentCall = {},
                        onSimulateCall = {},
                        onBack = {},
                        onContinue = {}
                    )
                }
                WizardStep.TEST_RESULT -> {
                    Step4TestResult(
                        recentCallEvent = sampleCall,
                        gateResult = GateEvaluationResult.Allowed(sampleLeads.first(), sampleCall),
                        matchingResult = sampleMatch,
                        candidateRecordings = listOf(sampleCandidate),
                        isCallSimulated = false,
                        isSearchingRecording = false,
                        uploadState = UploadState.Idle,
                        deviceModel = "Vivo V2111",
                        onStartNewTest = {},
                        onRequestUpload = {},
                        onRetryUpload = {},
                        onOpenAdvancedDiagnostics = {}
                    )
                }
            }
        }
    }
}

/**
 * Preview 1: Step 1 on Narrow Phone (320dp, e.g. Vivo V2111 / Galaxy A06)
 */
@Preview(name = "Step 1: Permissions (320dp Narrow)", widthDp = 320, heightDp = 800, showBackground = true)
@Composable
fun PreviewStep1Narrow() {
    Leads365Theme {
        PreviewWizardStep(WizardStep.PERMISSIONS)
    }
}

/**
 * Preview 2: Step 2 on Standard Phone (360dp)
 */
@Preview(name = "Step 2: Folder Selection (360dp)", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
fun PreviewStep2Folder() {
    Leads365Theme {
        PreviewWizardStep(WizardStep.RECORDING_FOLDER)
    }
}

/**
 * Preview 3: Step 3 on Standard Phone (360dp)
 */
@Preview(name = "Step 3: Test Call (360dp)", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
fun PreviewStep3Call() {
    Leads365Theme {
        PreviewWizardStep(WizardStep.TEST_CALL)
    }
}

/**
 * Preview 4: Step 4 Result on Large Phone (412dp)
 */
@Preview(name = "Step 4: Result (412dp)", widthDp = 412, heightDp = 850, showBackground = true)
@Composable
fun PreviewStep4Result() {
    Leads365Theme {
        PreviewWizardStep(WizardStep.TEST_RESULT)
    }
}

/**
 * Preview 5: Dark Theme Preview
 */
@Preview(name = "Dark Theme Wizard (360dp)", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
fun PreviewDarkWizard() {
    Leads365Theme(darkTheme = true) {
        PreviewWizardStep(WizardStep.TEST_RESULT)
    }
}
