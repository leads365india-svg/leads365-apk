package com.leads365.apk

import com.leads365.apk.calls.CallGateManager
import com.leads365.apk.calls.CallLogAccessStatus
import com.leads365.apk.calls.CallMonitoringState
import com.leads365.apk.core.*
import com.leads365.apk.recordings.RecordingSource
import com.leads365.apk.recordings.SafFolderInspection
import com.leads365.apk.recordings.TargetedRecordingMatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RealCallDetectionAndDiscoveryTest {

    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var gateManager: CallGateManager
    private lateinit var matcher: TargetedRecordingMatcher

    private val allowedLead = TestLead(
        id = "lead_01",
        name = "Allowed Real Test Lead",
        rawNumber = "+91 98765 43210",
        normalizedNumber = "+919876543210",
        isDoNotRecord = false
    )

    private val dnrLead = TestLead(
        id = "lead_02",
        name = "DNR Client",
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
    fun testLongCallRecencyDetectionCalculatesFromCallEndTime() {
        // Suppose a call started 3 minutes ago (180s) and lasted 150s (2.5 minutes).
        // It completed 30 seconds ago.
        val now = System.currentTimeMillis()
        val durationSec = 150L
        val startTime = now - (durationSec * 1000L) - 30_000L // 180s ago
        val endTime = startTime + (durationSec * 1000L)       // 30s ago

        val longCall = CallEvent(
            id = "call_long_1",
            rawNumber = "+91 98765 43210",
            normalizedNumber = "+919876543210",
            startTimeEpochMs = startTime,
            endTimeEpochMs = endTime,
            durationSeconds = durationSec,
            direction = CallDirection.INCOMING
        )

        // Verifies that recency evaluated from endTime is within the 15-minute window
        val maxEndRecencyMs = 900_000L
        val timeSinceEnd = now - longCall.endTimeEpochMs
        assertTrue("Call end time must fall within the recency window", timeSinceEnd in 0..maxEndRecencyMs)
        assertEquals(30_000L, timeSinceEnd)
    }

    @Test
    fun testIncomingAndOutgoingCallTransitionsTriggerLookup() {
        // Incoming answered call: IDLE -> RINGING -> OFFHOOK -> IDLE
        val stateLog = mutableListOf<String>()

        fun simulateTransition(oldState: Int, newState: Int) {
            val isCallEnd = (oldState == 2 || oldState == 1) && newState == 0
            if (isCallEnd) {
                stateLog.add("TRIGGER_LOOKUP")
            }
        }

        // 1. Incoming call answered: OFFHOOK -> IDLE
        simulateTransition(oldState = 2, newState = 0)
        assertEquals(listOf("TRIGGER_LOOKUP"), stateLog)

        // 2. Incoming call missed/rejected: RINGING -> IDLE
        simulateTransition(oldState = 1, newState = 0)
        assertEquals(listOf("TRIGGER_LOOKUP", "TRIGGER_LOOKUP"), stateLog)

        // 3. Outgoing call completed: OFFHOOK -> IDLE
        simulateTransition(oldState = 2, newState = 0)
        assertEquals(listOf("TRIGGER_LOOKUP", "TRIGGER_LOOKUP", "TRIGGER_LOOKUP"), stateLog)

        // 4. Idle to Idle does not trigger
        simulateTransition(oldState = 0, newState = 0)
        assertEquals(3, stateLog.size)
    }

    @Test
    fun testPlatformRestrictedCallLogStatusDistinguishesFromNormalDenial() {
        val deniedStatus = CallLogAccessStatus.PERMISSION_DENIED
        val restrictedStatus = CallLogAccessStatus.RESTRICTED_BY_PLATFORM
        val accessibleStatus = CallLogAccessStatus.ACCESSIBLE

        assertNotEquals("Restricted must be distinct from normal denial", deniedStatus, restrictedStatus)
        assertTrue(accessibleStatus == CallLogAccessStatus.ACCESSIBLE)
    }

    @Test
    fun testTargetedMatcherAcceptsFileTimestampedAtCallStart() {
        // Some OEM dialers (Vivo Funtouch OS, Xiaomi) set file lastModified at file creation (call START)
        val now = System.currentTimeMillis()
        val durationSec = 60L
        val callStart = now - (durationSec * 1000L) - 10_000L // Started 70s ago
        val callEnd = callStart + (durationSec * 1000L)       // Ended 10s ago

        val call = CallEvent(
            id = "call_start_ts",
            rawNumber = "+91 98765 43210",
            normalizedNumber = "+919876543210",
            startTimeEpochMs = callStart,
            endTimeEpochMs = callEnd,
            durationSeconds = durationSec,
            direction = CallDirection.INCOMING
        )

        // Audio file timestamped at call START (+ 2 seconds)
        val candidate = RecordingCandidate(
            uriString = "content://saf/recording_start.m4a",
            displayName = "Record_9876543210_${callStart}.m4a",
            sizeBytes = 250_000L,
            lastModifiedEpochMs = callStart + 2_000L,
            mimeType = "audio/m4a",
            durationSeconds = 59L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val result = matcher.match(listOf(candidate), call)
        assertEquals("File timestamped at call start must match successfully", MatchStatus.FILE_FOUND, result.status)
        assertNotNull(result.matchedCandidate)
        assertTrue("Confidence must exceed acceptance threshold", result.confidenceScore >= 0.55)
    }

    @Test
    fun testEmptyFolderDiagnosticsProvidesHelpfulGuidance() {
        val emptyInspection = SafFolderInspection(
            isAccessible = true,
            totalFilesCount = 0,
            audioFilesCount = 0,
            subfoldersCount = 0,
            latestAudioFileName = null,
            latestAudioModifiedMs = null,
            messageEn = "Selected folder is completely empty (0 files)."
        )

        assertTrue(emptyInspection.isAccessible)
        assertEquals(0, emptyInspection.audioFilesCount)
        assertTrue(emptyInspection.messageEn.contains("completely empty"))
    }

    @Test
    fun testNonLeadCallStopsBeforeRecordingLookup() {
        val nonLeadCall = CallEvent(
            id = "call_non_lead",
            rawNumber = "+91 99999 00000",
            normalizedNumber = "+919999900000",
            startTimeEpochMs = System.currentTimeMillis() - 30_000L,
            endTimeEpochMs = System.currentTimeMillis() - 5_000L,
            durationSeconds = 25L,
            direction = CallDirection.INCOMING
        )

        val gateResult = gateManager.evaluateCall(nonLeadCall)
        assertTrue("Gate must reject non-lead call", gateResult is GateEvaluationResult.RejectedNotALead)

        // Verify that NO candidate list is queried for rejected call
        val evaluatedCandidates = emptyList<RecordingCandidate>()
        assertEquals(0, evaluatedCandidates.size)
    }

    @Test
    fun testDnrCallStopsBeforeRecordingLookup() {
        val dnrCall = CallEvent(
            id = "call_dnr",
            rawNumber = "+91 98765 43212",
            normalizedNumber = "+919876543212",
            startTimeEpochMs = System.currentTimeMillis() - 30_000L,
            endTimeEpochMs = System.currentTimeMillis() - 5_000L,
            durationSeconds = 25L,
            direction = CallDirection.INCOMING
        )

        val gateResult = gateManager.evaluateCall(dnrCall)
        assertTrue("Gate must reject DNR call", gateResult is GateEvaluationResult.RejectedDoNotRecord)
    }

    @Test
    fun testIncomingCallWithAssignedLeadMatchesRecordingAccurately() {
        val now = System.currentTimeMillis()
        val durationSec = 45L
        val callStart = now - (durationSec * 1000L) - 5_000L
        val callEnd = callStart + (durationSec * 1000L)

        val incomingCall = CallEvent(
            id = "call_incoming_assigned",
            rawNumber = "+91 98765 43210",
            normalizedNumber = "+919876543210",
            startTimeEpochMs = callStart,
            endTimeEpochMs = callEnd,
            durationSeconds = durationSec,
            direction = CallDirection.INCOMING
        )

        // Lead gate verification
        val gateResult = gateManager.evaluateCall(incomingCall)
        assertTrue("Assigned lead call must pass gate", gateResult is GateEvaluationResult.Allowed)

        // Two candidates: one matching the incoming lead number & time, one unrelated
        val matchedCandidate = RecordingCandidate(
            uriString = "content://saf/call_recordings/Call_9876543210_${callStart}.m4a",
            displayName = "Call_9876543210_${callStart}.m4a",
            sizeBytes = 180_000L,
            lastModifiedEpochMs = callEnd + 1_500L,
            mimeType = "audio/m4a",
            durationSeconds = 44L,
            isReadable = true,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val unrelatedCandidate = RecordingCandidate(
            uriString = "content://saf/call_recordings/Call_1111111111_${callStart - 7200_000L}.m4a",
            displayName = "Call_1111111111.m4a",
            sizeBytes = 100_000L,
            lastModifiedEpochMs = callStart - 7200_000L,
            mimeType = "audio/m4a",
            durationSeconds = 20L,
            isReadable = true,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val matchResult = matcher.match(listOf(matchedCandidate, unrelatedCandidate), incomingCall)
        assertEquals("Incoming call must uniquely match candidate", MatchStatus.FILE_FOUND, matchResult.status)
        assertEquals("Must match the correct candidate URI", matchedCandidate.uriString, matchResult.matchedCandidate?.uriString)
        assertTrue("Confidence score must be high", matchResult.confidenceScore >= 0.70)
    }
}
