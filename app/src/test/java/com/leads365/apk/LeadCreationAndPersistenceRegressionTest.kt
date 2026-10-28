package com.leads365.apk

import com.leads365.apk.calls.CallGateManager
import com.leads365.apk.core.*
import com.leads365.apk.recordings.SafFolderInspection
import com.leads365.apk.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LeadCreationAndPersistenceRegressionTest {

    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var gateManager: CallGateManager

    @Before
    fun setUp() {
        normalizer = PhoneNumberNormalizer("IN")
        gateManager = CallGateManager(normalizer)
    }

    @Test
    fun testInitialLeadStateIsEmpty() {
        // Initial state must not contain hardcoded or stale test leads
        assertTrue("Initial test leads list must be empty", gateManager.getTestLeads().isEmpty())
        assertFalse(
            "Acme Realty Test Lead must not be seeded by default",
            gateManager.getTestLeads().any { it.name.contains("Acme", ignoreCase = true) }
        )
    }

    @Test
    fun testIndianPhoneNumberNormalizationAcrossFormats() {
        val testCases = listOf(
            "9876543210" to "+919876543210",
            "09876543210" to "+919876543210",
            "+91 98765 43210" to "+919876543210",
            "+919876543210" to "+919876543210",
            "919876543210" to "+919876543210"
        )

        for ((input, expected) in testCases) {
            val normalized = normalizer.normalizeToE164(input)
            assertEquals("Failed for input: $input", expected, normalized)
            assertEquals("10-digit extraction failed for input: $input", "9876543210", normalizer.extractLast10Digits(input))
            assertTrue("Should be valid phone number: $input", normalizer.isValid(input))
        }
    }

    @Test
    fun testPhoneValidationRejectsInvalidNumbers() {
        assertFalse("Blank string must be invalid", normalizer.isValid(""))
        assertFalse("Too few digits must be invalid", normalizer.isValid("12345"))
        assertFalse("Letters only must be invalid", normalizer.isValid("abcdefghij"))
        assertFalse("Null must be invalid", normalizer.isValid(null))
    }

    @Test
    fun testDuplicateDetectionAcrossDifferentFormats() {
        val lead1 = TestLead(
            id = "lead_1",
            name = "Ramesh Kumar",
            rawNumber = "+91 98765 43210",
            normalizedNumber = normalizer.normalizeToE164("+91 98765 43210")!!
        )
        gateManager.addTestLead(lead1)

        val candidateFormats = listOf(
            "9876543210",
            "09876543210",
            "+919876543210",
            "+91 98765 43210"
        )

        val existingLeads = gateManager.getTestLeads()
        for (candidate in candidateFormats) {
            val candidateNorm = normalizer.normalizeToE164(candidate) ?: candidate
            val candidateLast10 = normalizer.extractLast10Digits(candidateNorm)
            val isDuplicate = existingLeads.any { existing ->
                val existingNorm = existing.normalizedNumber
                val existingLast10 = normalizer.extractLast10Digits(existingNorm)
                existingNorm == candidateNorm || (candidateLast10.isNotEmpty() && candidateLast10 == existingLast10)
            }
            assertTrue("Candidate '$candidate' should be recognized as duplicate", isDuplicate)
        }
    }

    @Test
    fun testLeadSerializationAndDeserializationRoundtrip() {
        val originalLeads = listOf(
            TestLead(
                id = "lead_101",
                name = "Priya Sharma",
                rawNumber = "9876543210",
                normalizedNumber = "+919876543210",
                isDoNotRecord = false,
                notes = "Primary client",
                enabled = true
            ),
            TestLead(
                id = "lead_102",
                name = "Confidential Advisor",
                rawNumber = "+91 98765 43212",
                normalizedNumber = "+919876543212",
                isDoNotRecord = true,
                notes = "DNR account",
                enabled = true
            )
        )

        val jsonString = MainViewModel.serializeLeads(originalLeads)
        assertNotNull("JSON string should not be null", jsonString)
        assertTrue("JSON should contain lead ID", jsonString.contains("lead_101"))
        assertTrue("JSON should contain lead name", jsonString.contains("Priya Sharma"))
        assertTrue("JSON should contain DNR flag", jsonString.contains("isDoNotRecord"))

        val deserialized = MainViewModel.deserializeLeads(jsonString)
        assertEquals("List size should match after roundtrip", originalLeads.size, deserialized.size)

        val first = deserialized[0]
        assertEquals("lead_101", first.id)
        assertEquals("Priya Sharma", first.name)
        assertEquals("9876543210", first.rawNumber)
        assertEquals("+919876543210", first.normalizedNumber)
        assertFalse(first.isDoNotRecord)

        val second = deserialized[1]
        assertEquals("lead_102", second.id)
        assertTrue(second.isDoNotRecord)
    }

    @Test
    fun testCorruptedJsonDeserializationReturnsEmptyListSafely() {
        val badJson = "not a json string at all { broken"
        val result = MainViewModel.deserializeLeads(badJson)
        assertTrue("Bad JSON should return empty list without crashing", result.isEmpty())

        val emptyJson = ""
        val emptyResult = MainViewModel.deserializeLeads(emptyJson)
        assertTrue("Empty JSON should return empty list", emptyResult.isEmpty())
    }

    @Test
    fun testAllowedLeadPassesGateAndNonLeadIsBlocked() {
        val userLead = TestLead(
            id = "user_lead_01",
            name = "Test Lead Alpha",
            rawNumber = "9876543210",
            normalizedNumber = "+919876543210",
            isDoNotRecord = false
        )
        gateManager.addTestLead(userLead)

        // Matching call event
        val matchingCall = CallEvent(
            id = "call_match_01",
            rawNumber = "09876543210",
            normalizedNumber = "+919876543210",
            startTimeEpochMs = 1700000000000L,
            endTimeEpochMs = 1700000030000L,
            durationSeconds = 30L,
            direction = CallDirection.OUTGOING
        )
        val matchResult = gateManager.evaluateCall(matchingCall)
        assertTrue("Matching call must pass the lead gate", matchResult is GateEvaluationResult.Allowed)
        assertEquals("user_lead_01", (matchResult as GateEvaluationResult.Allowed).lead.id)

        // Non-lead call event (different number)
        val nonLeadCall = CallEvent(
            id = "call_non_lead_01",
            rawNumber = "+91 99999 11111",
            normalizedNumber = "+919999911111",
            startTimeEpochMs = 1700000040000L,
            endTimeEpochMs = 1700000060000L,
            durationSeconds = 20L,
            direction = CallDirection.INCOMING
        )
        val nonLeadResult = gateManager.evaluateCall(nonLeadCall)
        assertTrue("Non-lead call must be strictly blocked", nonLeadResult is GateEvaluationResult.RejectedNotALead)

        // DNR lead call event
        gateManager.toggleDoNotRecord("user_lead_01", true)
        val dnrCall = CallEvent(
            id = "call_dnr_01",
            rawNumber = "9876543210",
            normalizedNumber = "+919876543210",
            startTimeEpochMs = 1700000070000L,
            endTimeEpochMs = 1700000095000L,
            durationSeconds = 25L,
            direction = CallDirection.OUTGOING
        )
        val dnrResult = gateManager.evaluateCall(dnrCall)
        assertTrue("DNR lead call must be strictly blocked", dnrResult is GateEvaluationResult.RejectedDoNotRecord)
    }

    @Test
    fun testSafFolderInspectionConciseEnglishMessages() {
        val emptyFolderInspection = SafFolderInspection(
            isAccessible = true,
            totalFilesCount = 0,
            audioFilesCount = 0,
            subfoldersCount = 0,
            latestAudioFileName = null,
            latestAudioModifiedMs = null,
            messageEn = "Folder is empty. Make a call with automatic recording enabled in your phone dialer."
        )
        assertEquals(
            "Folder is empty. Make a call with automatic recording enabled in your phone dialer.",
            emptyFolderInspection.messageEn
        )

        val noAudioInspection = SafFolderInspection(
            isAccessible = true,
            totalFilesCount = 5,
            audioFilesCount = 0,
            subfoldersCount = 1,
            latestAudioFileName = null,
            latestAudioModifiedMs = null,
            messageEn = "Folder contains 5 file(s), but no audio recordings (.m4a, .mp3, .wav). Check dialer recording settings."
        )
        assertEquals(
            "Folder contains 5 file(s), but no audio recordings (.m4a, .mp3, .wav). Check dialer recording settings.",
            noAudioInspection.messageEn
        )

        val successInspection = SafFolderInspection(
            isAccessible = true,
            totalFilesCount = 3,
            audioFilesCount = 2,
            subfoldersCount = 0,
            latestAudioFileName = "Call_Recording_01.m4a",
            latestAudioModifiedMs = 1700000000000L,
            messageEn = "Found 2 audio recording(s). Ready for call matching."
        )
        assertEquals(
            "Found 2 audio recording(s). Ready for call matching.",
            successInspection.messageEn
        )

        val revokedInspection = SafFolderInspection(
            isAccessible = false,
            totalFilesCount = 0,
            audioFilesCount = 0,
            subfoldersCount = 0,
            latestAudioFileName = null,
            latestAudioModifiedMs = null,
            messageEn = "Folder access permission was revoked. Please select the folder again."
        )
        assertEquals(
            "Folder access permission was revoked. Please select the folder again.",
            revokedInspection.messageEn
        )
    }
}
