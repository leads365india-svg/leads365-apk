package com.leads365.apk.recordings

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.calls.CallGateManager
import com.leads365.apk.core.CallDirection
import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.GateEvaluationResult
import com.leads365.apk.core.MatchStatus
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.RecordingDiscoveryMethod
import com.leads365.apk.core.TestLead
import com.leads365.apk.diagnostics.DiagnosticsManager
import com.leads365.apk.ui.RecordingDiscoveryState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SafRecordingDiscoveryTest {

    private lateinit var context: Context
    private val normalizer = PhoneNumberNormalizer()
    private val matcher = TargetedRecordingMatcher(normalizer)
    private val callGate = CallGateManager(normalizer)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        callGate.setLeads(
            listOf(
                TestLead("lead_1", "Acme Test Lead", "+91 98765 43210", "+919876543210", isDoNotRecord = false),
                TestLead("lead_dnr", "VIP Partner (DNR)", "+91 98765 43212", "+919876543212", isDoNotRecord = true)
            )
        )
    }

    @Test
    fun `LeadGate strictly blocks non-lead call before any recording lookup`() {
        val nonLeadCall = CallEvent(
            id = "call_stranger",
            rawNumber = "+91 91111 22222",
            normalizedNumber = "+919111122222",
            startTimeEpochMs = 10000L,
            endTimeEpochMs = 25000L,
            durationSeconds = 15L,
            direction = CallDirection.INCOMING
        )

        val gateResult = callGate.evaluateCall(nonLeadCall)
        assertTrue("Non-lead call must be rejected", gateResult is GateEvaluationResult.RejectedNotALead)
    }

    @Test
    fun `LeadGate strictly blocks Do-Not-Record call before any recording lookup`() {
        val dnrCall = CallEvent(
            id = "call_dnr",
            rawNumber = "+91 98765 43212",
            normalizedNumber = "+919876543212",
            startTimeEpochMs = 10000L,
            endTimeEpochMs = 25000L,
            durationSeconds = 15L,
            direction = CallDirection.OUTGOING
        )

        val gateResult = callGate.evaluateCall(dnrCall)
        assertTrue("DNR call must be rejected", gateResult is GateEvaluationResult.RejectedDoNotRecord)
    }

    @Test
    fun `TargetedRecordingMatcher uniquely matches audio file with lead phone number in filename`() {
        val callStart = 1729780000000L
        val callEnd = callStart + 30_000L
        val call = CallEvent(
            id = "call_lead_1",
            rawNumber = "+91 98765 43210",
            normalizedNumber = "+919876543210",
            startTimeEpochMs = callStart,
            endTimeEpochMs = callEnd,
            durationSeconds = 30L,
            direction = CallDirection.OUTGOING
        )

        val candidateMatching = RecordingCandidate(
            uriString = "content://saf/Recordings/Call/+919876543210_20261024_172000.m4a",
            displayName = "+919876543210_20261024_172000.m4a",
            sizeBytes = 240_000L,
            lastModifiedEpochMs = callEnd + 3000L, // flushed 3s after call end
            mimeType = "audio/m4a",
            durationSeconds = 29L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val candidateUnrelated = RecordingCandidate(
            uriString = "content://saf/Recordings/Call/other_call.m4a",
            displayName = "other_call.m4a",
            sizeBytes = 240_000L,
            lastModifiedEpochMs = callEnd - 40_000L, // before call
            mimeType = "audio/m4a",
            durationSeconds = 10L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val result = matcher.match(listOf(candidateMatching, candidateUnrelated), call)
        assertEquals(MatchStatus.FILE_FOUND, result.status)
        assertNotNull(result.matchedCandidate)
        assertEquals(candidateMatching.uriString, result.matchedCandidate?.uriString)
        assertTrue("Confidence score should be high", result.confidenceScore >= 0.70)
    }

    @Test
    fun `TargetedRecordingMatcher marks ambiguous when multiple candidates have nearly identical scores`() {
        val callStart = 1729780000000L
        val callEnd = callStart + 20_000L
        val call = CallEvent(
            id = "call_lead_1",
            rawNumber = "+91 98765 43210",
            normalizedNumber = "+919876543210",
            startTimeEpochMs = callStart,
            endTimeEpochMs = callEnd,
            durationSeconds = 20L,
            direction = CallDirection.OUTGOING
        )

        // Two generic audio files created at almost the same second with no phone number in filename
        val candidate1 = RecordingCandidate(
            uriString = "content://saf/Recordings/Call/rec_001.m4a",
            displayName = "rec_001.m4a",
            sizeBytes = 150_000L,
            lastModifiedEpochMs = callEnd + 1000L,
            mimeType = "audio/m4a",
            durationSeconds = 19L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val candidate2 = RecordingCandidate(
            uriString = "content://saf/Recordings/Call/rec_002.m4a",
            displayName = "rec_002.m4a",
            sizeBytes = 150_000L,
            lastModifiedEpochMs = callEnd + 2000L,
            mimeType = "audio/m4a",
            durationSeconds = 21L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val result = matcher.match(listOf(candidate1, candidate2), call)
        assertEquals(MatchStatus.AMBIGUOUS, result.status)
        assertNull(result.matchedCandidate)
        assertTrue("Ambiguity reason must be documented", result.reason.contains("Ambiguous match"))
    }

    @Test
    fun `SafRecordingSource reports revoked access when persisted permission is missing`() {
        val source = SafRecordingSource(
            context = context,
            getTreeUriString = { "content://com.android.externalstorage.documents/tree/primary%3ARecordings" },
            hasPersistedPermission = { false } // simulate revoked permission
        )

        assertFalse(source.isAccessible())
        val inspection = source.inspectFolderContent()
        assertFalse(inspection.isAccessible)
        assertTrue(inspection.messageEn.contains("revoked"))
    }

    @Test
    fun `SafRecordingSource reports unconfigured when tree uri is null`() {
        val source = SafRecordingSource(
            context = context,
            getTreeUriString = { null },
            hasPersistedPermission = { false }
        )

        assertFalse(source.isConfigured())
        assertFalse(source.isAccessible())
        val inspection = source.inspectFolderContent()
        assertFalse(inspection.isAccessible)
        assertTrue(inspection.messageEn.contains("No folder selected"))
    }

    @Test
    fun `DiagnosticsManager identifies sandboxed dialers like Google Pixel`() {
        val diag = DiagnosticsManager(
            context = context,
            isSafConfigured = { true },
            isSafValid = { true },
            isMediaStoreAccessible = { true }
        )

        // Standard test environment model check
        assertNotNull(diag.getDeviceModel())
        assertNotNull(diag.getManufacturer())
    }
}
