package com.leads365.apk

import com.leads365.apk.core.CallDirection
import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.MatchStatus
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.RecordingDiscoveryMethod
import com.leads365.apk.recordings.TargetedRecordingMatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TargetedRecordingMatcherTest {

    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var matcher: TargetedRecordingMatcher

    private val baseCall = CallEvent(
        id = "test_call_01",
        rawNumber = "+91 98765 43210",
        normalizedNumber = "+919876543210",
        startTimeEpochMs = 1700000000000L,
        endTimeEpochMs = 1700000060000L, // 60s call
        durationSeconds = 60L,
        direction = CallDirection.OUTGOING
    )

    @Before
    fun setUp() {
        normalizer = PhoneNumberNormalizer("IN")
        matcher = TargetedRecordingMatcher(normalizer, minAcceptanceScore = 0.55, ambiguityDeltaThreshold = 0.15)
    }

    @Test
    fun testUniqueMatchingCandidateProducesFileFound() {
        // Ideal candidate: modified 5s after call end, matching duration, contains phone number
        val idealCandidate = RecordingCandidate(
            uriString = "content://recordings/101",
            displayName = "Call_+919876543210_20261024.m4a",
            sizeBytes = 250_000L,
            lastModifiedEpochMs = baseCall.endTimeEpochMs + 5_000L,
            mimeType = "audio/m4a",
            durationSeconds = 60L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val result = matcher.match(listOf(idealCandidate), baseCall)
        assertEquals(MatchStatus.FILE_FOUND, result.status)
        assertNotNull(result.matchedCandidate)
        assertEquals("Call_+919876543210_20261024.m4a", result.matchedCandidate?.displayName)
        assertTrue("Confidence score should be high", result.confidenceScore >= 0.80)
    }

    @Test
    fun testAbsentCandidatesProducesNoFileFound() {
        val result = matcher.match(emptyList(), baseCall)
        assertEquals(MatchStatus.NO_FILE_FOUND, result.status)
        assertNull(result.matchedCandidate)
        assertEquals(0, result.candidateCount)
    }

    @Test
    fun testWrongDurationCandidateRejected() {
        // Candidate is 15 minutes long (900s) for a 60s call, and generic filename
        val wrongDurationCandidate = RecordingCandidate(
            uriString = "content://recordings/102",
            displayName = "MusicTrack.mp3",
            sizeBytes = 5_000_000L,
            lastModifiedEpochMs = baseCall.endTimeEpochMs + 10_000L,
            mimeType = "audio/mp3",
            durationSeconds = 900L,
            sourceMethod = RecordingDiscoveryMethod.MEDIA_STORE
        )

        val result = matcher.match(listOf(wrongDurationCandidate), baseCall)
        assertEquals(MatchStatus.NO_FILE_FOUND, result.status)
        assertNull(result.matchedCandidate)
    }

    @Test
    fun testDelayedRecordingWriterHandledWithinWindow() {
        // File flushed 70s after call end by OEM dialer
        val delayedCandidate = RecordingCandidate(
            uriString = "content://recordings/103",
            displayName = "Rec_9876543210.m4a",
            sizeBytes = 240_000L,
            lastModifiedEpochMs = baseCall.endTimeEpochMs + 70_000L,
            mimeType = "audio/m4a",
            durationSeconds = 59L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val result = matcher.match(listOf(delayedCandidate), baseCall)
        assertEquals(MatchStatus.FILE_FOUND, result.status)
        assertNotNull(result.matchedCandidate)
    }

    @Test
    fun testAmbiguousMatchWhenMultipleCandidatesHaveCloseScores() {
        // Two candidates created 3s apart with identical duration and generic dialer names
        val candA = RecordingCandidate(
            uriString = "content://recordings/201",
            displayName = "Call_Recording_A.m4a",
            sizeBytes = 245_000L,
            lastModifiedEpochMs = baseCall.endTimeEpochMs + 4_000L,
            mimeType = "audio/m4a",
            durationSeconds = 60L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )
        val candB = RecordingCandidate(
            uriString = "content://recordings/202",
            displayName = "Call_Recording_B.m4a",
            sizeBytes = 246_000L,
            lastModifiedEpochMs = baseCall.endTimeEpochMs + 7_000L,
            mimeType = "audio/m4a",
            durationSeconds = 60L,
            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
        )

        val result = matcher.match(listOf(candA, candB), baseCall)
        assertEquals(MatchStatus.AMBIGUOUS, result.status)
        assertNull("Ambiguous match must NOT return an arbitrary candidate", result.matchedCandidate)
        assertTrue(result.reason.contains("Ambiguous match"))
    }
}
