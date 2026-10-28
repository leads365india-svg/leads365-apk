package com.leads365.apk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.leads365.apk.calls.CallLogDiagnosticResult
import com.leads365.apk.core.*
import com.leads365.apk.diagnostics.DiagnosticsManager
import com.leads365.apk.ui.MainUiState
import com.leads365.apk.ui.WizardStep
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
class Phase4PermissionsAndOnboardingTest {

    private val mockContext = object : android.content.ContextWrapper(null) {
        override fun getPackageName(): String = "com.leads365.apk"
    }

    @Test
    fun testMandatoryPermissionsGrantedAllowsStep2Progression() {
        val grantedList = listOf(
            PermissionItem("perm_phone_state", "Phone State", "Detect call end", PermissionStatus.GRANTED, isMandatory = true),
            PermissionItem("perm_call_log", "Call Log", "Read number & time", PermissionStatus.GRANTED, isMandatory = true),
            PermissionItem("perm_saf_folder", "SAF Folder", "Recording folder", PermissionStatus.NOT_REQUESTED, isMandatory = false),
            PermissionItem("perm_call_phone", "Direct Calling", "One tap call", PermissionStatus.NOT_GRANTED, isMandatory = false),
            PermissionItem("perm_notifications", "Notifications", "Alerts", PermissionStatus.NOT_GRANTED, isMandatory = false),
            PermissionItem("perm_overlay", "Overlay", "Caller popup", PermissionStatus.NOT_GRANTED, isMandatory = false),
            PermissionItem("perm_battery_opt", "Battery Opt", "Background execution", PermissionStatus.NOT_GRANTED, isMandatory = false)
        )
        val state = MainUiState(permissions = grantedList)
        assertTrue("When mandatory permissions are granted, canContinueToStep2 must be true", state.canContinueToStep2)
    }

    @Test
    fun testMandatoryPermissionDeniedOrRevokedBlocksStep2() {
        // Phone state denied
        val phoneDenied = listOf(
            PermissionItem("perm_phone_state", "Phone State", "Detect call end", PermissionStatus.DENIED, isMandatory = true),
            PermissionItem("perm_call_log", "Call Log", "Read number & time", PermissionStatus.GRANTED, isMandatory = true)
        )
        assertFalse("Denied phone state must block Step 2", MainUiState(permissions = phoneDenied).canContinueToStep2)

        // Call log restricted by system privacy guard
        val callLogRestricted = listOf(
            PermissionItem("perm_phone_state", "Phone State", "Detect call end", PermissionStatus.GRANTED, isMandatory = true),
            PermissionItem("perm_call_log", "Call Log", "Read number & time", PermissionStatus.RESTRICTED, isMandatory = true)
        )
        assertFalse("Restricted call log must block Step 2", MainUiState(permissions = callLogRestricted).canContinueToStep2)

        // Call log revoked / not granted
        val callLogNotGranted = listOf(
            PermissionItem("perm_phone_state", "Phone State", "Detect call end", PermissionStatus.GRANTED, isMandatory = true),
            PermissionItem("perm_call_log", "Call Log", "Read number & time", PermissionStatus.NOT_GRANTED, isMandatory = true)
        )
        assertFalse("Not granted call log must block Step 2", MainUiState(permissions = callLogNotGranted).canContinueToStep2)
    }

    @Test
    fun testOptionalPermissionsDoNotBlockProgression() {
        // Mandatory permissions are granted, all optional permissions are not granted
        val items = listOf(
            PermissionItem("perm_phone_state", "Phone State", "Detect call end", PermissionStatus.GRANTED, isMandatory = true),
            PermissionItem("perm_call_log", "Call Log", "Read number & time", PermissionStatus.GRANTED, isMandatory = true),
            PermissionItem("perm_saf_folder", "SAF Folder", "Recording folder", PermissionStatus.NOT_REQUESTED, isMandatory = false),
            PermissionItem("perm_call_phone", "Direct Calling", "One tap call", PermissionStatus.NOT_GRANTED, isMandatory = false),
            PermissionItem("perm_notifications", "Notifications", "Alerts", PermissionStatus.NOT_GRANTED, isMandatory = false),
            PermissionItem("perm_overlay", "Overlay", "Caller popup", PermissionStatus.NOT_GRANTED, isMandatory = false),
            PermissionItem("perm_battery_opt", "Battery Opt", "Background execution", PermissionStatus.NOT_GRANTED, isMandatory = false)
        )
        val state = MainUiState(permissions = items)
        assertTrue("Optional permissions must never block canContinueToStep2", state.canContinueToStep2)
    }

    @Test
    fun testSafPermissionLossAndRevocationDetection() {
        // 1. SAF configured and accessible
        var safConfigured = true
        var safValid = true
        val diagValid = DiagnosticsManager(
            context = mockContext,
            isSafConfigured = { safConfigured },
            isSafValid = { safValid },
            isMediaStoreAccessible = { false },
            diagnoseCallLog = { CallLogDiagnosticResult.PERMISSION_GRANTED_QUERY_SUCCESS }
        )
        val permsValid = diagValid.checkPermissions()
        val safItemValid = permsValid.first { it.id == "perm_saf_folder" }
        assertEquals("SAF status must be GRANTED when valid", PermissionStatus.GRANTED, safItemValid.status)
        assertTrue(safItemValid.rationale.contains("active"))

        // 2. SAF permission revoked or folder deleted (configured = true, valid = false)
        safValid = false
        val diagRevoked = DiagnosticsManager(
            context = mockContext,
            isSafConfigured = { safConfigured },
            isSafValid = { safValid },
            isMediaStoreAccessible = { false },
            diagnoseCallLog = { CallLogDiagnosticResult.PERMISSION_GRANTED_QUERY_SUCCESS }
        )
        val permsRevoked = diagRevoked.checkPermissions()
        val safItemRevoked = permsRevoked.first { it.id == "perm_saf_folder" }
        assertEquals("Revoked SAF folder must be marked RESTRICTED", PermissionStatus.RESTRICTED, safItemRevoked.status)
        assertTrue(safItemRevoked.rationale.contains("revoked or lost"))

        // 3. UI Gating: Lost SAF access without MediaStore blocks Step 3
        val stateLostSaf = MainUiState(safConfigured = true, safValid = false, mediaStoreAccessible = false)
        assertFalse("Lost SAF access must block canContinueToStep3", stateLostSaf.canContinueToStep3)
    }

    @Test
    fun testMediaStoreFallbackGating() {
        // SAF not configured, but MediaStore accessible
        val mediaStoreOnlyState = MainUiState(safConfigured = false, safValid = false, mediaStoreAccessible = true)
        assertTrue("MediaStore accessibility must allow entry to Step 3", mediaStoreOnlyState.canContinueToStep3)

        // Neither SAF nor MediaStore accessible
        val neitherState = MainUiState(safConfigured = false, safValid = false, mediaStoreAccessible = false)
        assertFalse("When neither SAF nor MediaStore is accessible, canContinueToStep3 must be false", neitherState.canContinueToStep3)
    }

    @Test
    fun testTruthfulRecordingVerificationStatus() {
        // 1. Connected folder ALONE does NOT count as recording verified
        val folderConnectedState = MainUiState(
            safConfigured = true,
            safValid = true,
            matchingResult = null
        )
        assertFalse(
            "Connecting a folder alone must NEVER count as a successful recording verification",
            folderConnectedState.isRecordingVerified
        )

        // 2. Matching result is NO_FILE_FOUND -> not verified
        val noFileState = MainUiState(
            safConfigured = true,
            safValid = true,
            matchingResult = RecordingMatchResult(status = MatchStatus.NO_FILE_FOUND)
        )
        assertFalse("NO_FILE_FOUND must not be verified", noFileState.isRecordingVerified)

        // 3. Matching result is AMBIGUOUS -> not verified
        val ambiguousState = MainUiState(
            safConfigured = true,
            safValid = true,
            matchingResult = RecordingMatchResult(status = MatchStatus.AMBIGUOUS)
        )
        assertFalse("AMBIGUOUS match must not be verified", ambiguousState.isRecordingVerified)

        // 4. Real audio file matched -> VERIFIED
        val verifiedCandidate = RecordingCandidate(
            uriString = "content://recordings/test.m4a",
            displayName = "Call_+919876543210_261024.m4a",
            sizeBytes = 120_000L,
            lastModifiedEpochMs = System.currentTimeMillis(),
            mimeType = "audio/m4a",
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )
        val verifiedState = MainUiState(
            safConfigured = true,
            safValid = true,
            matchingResult = RecordingMatchResult(
                status = MatchStatus.FILE_FOUND,
                matchedCandidate = verifiedCandidate
            )
        )
        assertTrue("FILE_FOUND with candidate must be verified", verifiedState.isRecordingVerified)
    }

    @Test
    fun testDialerRecordingConfirmationState() {
        val unconfirmed = MainUiState(dialerRecordingConfirmed = false)
        assertFalse(unconfirmed.dialerRecordingConfirmed)

        val confirmed = unconfirmed.copy(dialerRecordingConfirmed = true)
        assertTrue(confirmed.dialerRecordingConfirmed)
    }

    @Test
    fun testStrictPrivacyNoDangerousBypassPermissions() {
        val diag = DiagnosticsManager(
            context = mockContext,
            isSafConfigured = { true },
            isSafValid = { true },
            isMediaStoreAccessible = { true },
            diagnoseCallLog = { CallLogDiagnosticResult.PERMISSION_GRANTED_QUERY_SUCCESS }
        )
        val items = diag.checkPermissions()

        val itemIds = items.map { it.id.lowercase() }
        val titles = items.map { it.title.lowercase() }

        // Must NOT request microphone, contacts, sms, or location
        for (id in itemIds) {
            assertFalse("Must not request microphone access", id.contains("mic") || id.contains("record_audio"))
            assertFalse("Must not request contacts access", id.contains("contact"))
            assertFalse("Must not request SMS access", id.contains("sms"))
            assertFalse("Must not request location access", id.contains("location"))
        }

        for (title in titles) {
            assertFalse("Title must not mention microphone", title.contains("microphone"))
            assertFalse("Title must not mention contacts", title.contains("contacts"))
            assertFalse("Title must not mention sms", title.contains("sms"))
            assertFalse("Title must not mention location", title.contains("location"))
        }
    }

    @Test
    fun testAppResumeRefreshRecoversState() {
        // Simulate app in Step 1 where user was blocked by missing permission
        var callLogGranted = false
        val diag = DiagnosticsManager(
            context = mockContext,
            isSafConfigured = { true },
            isSafValid = { true },
            isMediaStoreAccessible = { true },
            diagnoseCallLog = {
                if (callLogGranted) CallLogDiagnosticResult.PERMISSION_GRANTED_QUERY_SUCCESS
                else CallLogDiagnosticResult.PERMISSION_NOT_GRANTED
            }
        )

        // Initially blocked
        val initialPerms = diag.checkPermissions()
        val initialState = MainUiState(permissions = initialPerms)
        assertFalse("Initially should be blocked without call log", initialState.canContinueToStep2)

        // User goes to Android Settings, allows permission, and resumes the app
        callLogGranted = true
        val refreshedPerms = diag.checkPermissions()
        val refreshedState = initialState.copy(permissions = refreshedPerms)
        assertTrue("Refreshed state after app resume must allow progression", refreshedState.canContinueToStep2)
    }
}
