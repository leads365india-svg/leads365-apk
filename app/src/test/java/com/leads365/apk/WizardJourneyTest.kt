package com.leads365.apk

import com.leads365.apk.calls.CallGateManager
import com.leads365.apk.core.*
import com.leads365.apk.recordings.TargetedRecordingMatcher
import com.leads365.apk.ui.MainUiState
import com.leads365.apk.ui.WizardStep
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WizardJourneyTest {

    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var gateManager: CallGateManager
    private lateinit var matcher: TargetedRecordingMatcher

    private val allowedLead = TestLead(
        id = "lead_test_01",
        name = "Allowed Test Lead",
        rawNumber = "+91 98765 43210",
        normalizedNumber = "+919876543210",
        isDoNotRecord = false
    )

    private val dnrLead = TestLead(
        id = "lead_test_02_dnr",
        name = "VIP DNR Partner",
        rawNumber = "+91 98765 43212",
        normalizedNumber = "+919876543212",
        isDoNotRecord = true
    )

    @Before
    fun setUp() {
        normalizer = PhoneNumberNormalizer("IN")
        gateManager = CallGateManager(normalizer, listOf(allowedLead, dnrLead))
        matcher = TargetedRecordingMatcher(normalizer)
    }

    @Test
    fun testInitialOnboardingStateStartsAtStep1Permissions() {
        val initialState = MainUiState()
        assertEquals("Initial wizard step must be PERMISSIONS", WizardStep.PERMISSIONS, initialState.currentStep)
        assertFalse("Cannot continue to Step 2 without permissions", initialState.canContinueToStep2)
        assertFalse("Cannot continue to Step 3 without folder or MediaStore", initialState.canContinueToStep3)
        assertFalse("Cannot continue to Step 4 without a call event", initialState.canContinueToStep4)
    }

    @Test
    fun testPermissionDeniedAndGrantedStatesControlStep2Gating() {
        val deniedPermissions = listOf(
            PermissionItem("phone", "Phone State", "Detect call end", PermissionStatus.DENIED, isMandatory = true),
            PermissionItem("call_log", "Call Log", "Read number & time", PermissionStatus.DENIED, isMandatory = true),
            PermissionItem("audio", "Audio Access", "Find recording", PermissionStatus.NOT_REQUESTED, isMandatory = false)
        )
        val deniedState = MainUiState(permissions = deniedPermissions)
        assertFalse("Step 2 Continue button must be disabled when mandatory permissions are denied", deniedState.canContinueToStep2)

        val grantedPermissions = listOf(
            PermissionItem("phone", "Phone State", "Detect call end", PermissionStatus.GRANTED, isMandatory = true),
            PermissionItem("call_log", "Call Log", "Read number & time", PermissionStatus.GRANTED, isMandatory = true),
            PermissionItem("audio", "Audio Access", "Find recording", PermissionStatus.GRANTED, isMandatory = false)
        )
        val grantedState = MainUiState(permissions = grantedPermissions)
        assertTrue("Step 2 Continue button must be enabled when mandatory permissions are granted", grantedState.canContinueToStep2)
    }

    @Test
    fun testReEntryAfterPermissionRevokedBlocksStep2() {
        // User had permissions previously
        val previouslyGranted = listOf(
            PermissionItem("phone", "Phone State", "Detect call end", PermissionStatus.GRANTED, isMandatory = true),
            PermissionItem("call_log", "Call Log", "Read number & time", PermissionStatus.GRANTED, isMandatory = true)
        )
        val state1 = MainUiState(currentStep = WizardStep.RECORDING_FOLDER, permissions = previouslyGranted)
        assertTrue(state1.canContinueToStep2)

        // Permission revoked in OS settings (e.g. Vivo/Android 12 permission manager)
        val revoked = listOf(
            PermissionItem("phone", "Phone State", "Detect call end", PermissionStatus.DENIED, isMandatory = true),
            PermissionItem("call_log", "Call Log", "Read number & time", PermissionStatus.DENIED, isMandatory = true)
        )
        val state2 = state1.copy(permissions = revoked)
        assertFalse("Revoked permissions must immediately disable Step 2 gating", state2.canContinueToStep2)
    }

    @Test
    fun testFolderGatingControlsStep3Entry() {
        // Folder not selected
        val noFolderState = MainUiState(safConfigured = false, safValid = false, mediaStoreAccessible = false)
        assertFalse("Step 3 entry must be blocked when folder is not selected", noFolderState.canContinueToStep3)

        // Folder selected but grant expired/revoked
        val expiredFolderState = MainUiState(safConfigured = true, safValid = false, mediaStoreAccessible = false)
        assertFalse("Step 3 entry must be blocked when folder grant is invalid", expiredFolderState.canContinueToStep3)

        // Folder selected and valid
        val validFolderState = MainUiState(safConfigured = true, safValid = true, mediaStoreAccessible = false)
        assertTrue("Step 3 entry must be allowed when SAF folder is valid", validFolderState.canContinueToStep3)

        // MediaStore alternative accessible
        val mediaStoreState = MainUiState(safConfigured = false, safValid = false, mediaStoreAccessible = true)
        assertTrue("Step 3 entry must be allowed when MediaStore is accessible", mediaStoreState.canContinueToStep3)
    }

    @Test
    fun testActualCallVsSimulatedEventLabeling() {
        val now = 1700000000000L
        val realCall = CallEvent(
            id = "real_call_1",
            rawNumber = "+91 98765 43210",
            normalizedNumber = "+919876543210",
            startTimeEpochMs = now,
            endTimeEpochMs = now + 30_000L,
            durationSeconds = 30L,
            direction = CallDirection.OUTGOING
        )
        val realState = MainUiState(
            recentCallEvent = realCall,
            isCallSimulated = false,
            gateResult = GateEvaluationResult.Allowed(allowedLead, realCall)
        )
        assertFalse("Live call must have isCallSimulated = false", realState.isCallSimulated)
        assertTrue("Live call allows proceeding to Step 4", realState.canContinueToStep4)

        val simCall = realCall.copy(id = "sim_call_1")
        val simState = MainUiState(
            recentCallEvent = simCall,
            isCallSimulated = true,
            gateResult = GateEvaluationResult.Allowed(allowedLead, simCall)
        )
        assertTrue("Simulated call must have isCallSimulated = true", simState.isCallSimulated)
        assertTrue("Simulated call also unlocks Step 4", simState.canContinueToStep4)
    }

    @Test
    fun testNonLeadAndDnrCallsHaltBeforeRecordingLookup() {
        val now = 1700000000000L

        // 1. Non-lead call
        val nonLeadCall = CallEvent(
            id = "non_lead",
            rawNumber = "+91 91111 22222",
            normalizedNumber = "+919111122222",
            startTimeEpochMs = now,
            endTimeEpochMs = now + 20_000L,
            durationSeconds = 20L,
            direction = CallDirection.INCOMING
        )
        val nonLeadEval = gateManager.evaluateCall(nonLeadCall)
        assertTrue("Non-lead call must be RejectedNotALead", nonLeadEval is GateEvaluationResult.RejectedNotALead)

        // 2. DNR Call
        val dnrCall = CallEvent(
            id = "dnr_call",
            rawNumber = "+91 98765 43212",
            normalizedNumber = "+919876543212",
            startTimeEpochMs = now,
            endTimeEpochMs = now + 25_000L,
            durationSeconds = 25L,
            direction = CallDirection.OUTGOING
        )
        val dnrEval = gateManager.evaluateCall(dnrCall)
        assertTrue("DNR call must be RejectedDoNotRecord", dnrEval is GateEvaluationResult.RejectedDoNotRecord)
    }

    @Test
    fun testRecordingFoundDelayedAbsentAndAmbiguousResults() {
        val now = 1700000000000L
        val call = CallEvent(
            id = "test_call",
            rawNumber = "+91 98765 43210",
            normalizedNumber = "+919876543210",
            startTimeEpochMs = now,
            endTimeEpochMs = now + 30_000L,
            durationSeconds = 30L,
            direction = CallDirection.OUTGOING
        )

        // A. No recording found
        val noFileResult = matcher.match(emptyList(), call)
        assertEquals("Empty candidates must produce NO_FILE_FOUND", MatchStatus.NO_FILE_FOUND, noFileResult.status)
        assertNull(noFileResult.matchedCandidate)

        // B. Unique recording found
        val candidateGood = RecordingCandidate(
            uriString = "content://rec/1",
            displayName = "Call_+919876543210_261024.m4a",
            sizeBytes = 240_000L,
            lastModifiedEpochMs = call.endTimeEpochMs + 5_000L,
            mimeType = "audio/m4a",
            durationSeconds = 30L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )
        val uniqueResult = matcher.match(listOf(candidateGood), call)
        assertEquals("Matching candidate must produce FILE_FOUND", MatchStatus.FILE_FOUND, uniqueResult.status)
        assertNotNull(uniqueResult.matchedCandidate)
        assertEquals("content://rec/1", uniqueResult.matchedCandidate?.uriString)

        // C. Ambiguous match (two close files)
        val candidateAmb1 = RecordingCandidate(
            uriString = "content://rec/amb1",
            displayName = "Call_Recording_A.m4a",
            sizeBytes = 240_000L,
            lastModifiedEpochMs = call.endTimeEpochMs + 4_000L,
            mimeType = "audio/m4a",
            durationSeconds = 30L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )
        val candidateAmb2 = RecordingCandidate(
            uriString = "content://rec/amb2",
            displayName = "Call_Recording_B.m4a",
            sizeBytes = 242_000L,
            lastModifiedEpochMs = call.endTimeEpochMs + 5_000L,
            mimeType = "audio/m4a",
            durationSeconds = 30L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )
        val ambResult = matcher.match(listOf(candidateAmb1, candidateAmb2), call)
        assertEquals("Close scores must produce AMBIGUOUS", MatchStatus.AMBIGUOUS, ambResult.status)
        assertNull("Ambiguous match must not select a candidate for upload", ambResult.matchedCandidate)
    }

    @Test
    fun testUploadConfirmationAndUploadGating() {
        val candidate = RecordingCandidate(
            uriString = "content://rec/upload_test",
            displayName = "Call_Upload.m4a",
            sizeBytes = 150_000L,
            lastModifiedEpochMs = 1700000000000L,
            mimeType = "audio/m4a",
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val uniqueMatch = RecordingMatchResult(
            status = MatchStatus.FILE_FOUND,
            matchedCandidate = candidate,
            candidateCount = 1,
            confidenceScore = 0.95
        )
        // Upload candidate is only non-null when FILE_FOUND
        assertNotNull(uniqueMatch.matchedCandidate)

        val ambiguousMatch = RecordingMatchResult(
            status = MatchStatus.AMBIGUOUS,
            matchedCandidate = null,
            candidateCount = 2,
            confidenceScore = 0.5
        )
        assertNull(ambiguousMatch.matchedCandidate)

        val noMatch = RecordingMatchResult(
            status = MatchStatus.NO_FILE_FOUND,
            matchedCandidate = null,
            candidateCount = 0
        )
        assertNull(noMatch.matchedCandidate)
    }
}
