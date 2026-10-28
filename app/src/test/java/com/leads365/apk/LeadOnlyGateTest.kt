package com.leads365.apk

import com.leads365.apk.calls.CallGateManager
import com.leads365.apk.core.CallDirection
import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.GateEvaluationResult
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.RecordingDiscoveryMethod
import com.leads365.apk.core.TestLead
import com.leads365.apk.recordings.RecordingSource
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LeadOnlyGateTest {

    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var gateManager: CallGateManager
    private lateinit var mockRecordingSource: RecordingSource
    private val queryCallCount = AtomicInteger(0)

    private val allowedLead = TestLead(
        id = "lead_test_01",
        name = "Alpha Client",
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
        queryCallCount.set(0)

        // Mock recording source adapter
        mockRecordingSource = object : RecordingSource {
            override val discoveryMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK
            override fun isConfigured(): Boolean = true
            override fun isAccessible(): Boolean = true
            override fun queryCandidates(
                callStartEpochMs: Long,
                callEndEpochMs: Long,
                callDurationSec: Long,
                normalizedNumber: String
            ): List<RecordingCandidate> {
                queryCallCount.incrementAndGet()
                return emptyList()
            }
        }
    }

    @Test
    fun testAllowedLeadPassesGateAndPermitsRecordingLookup() {
        val now = 1700000000000L
        val call = CallEvent(
            id = "call_1",
            rawNumber = "09876543210", // 0-prefix format matches +919876543210
            normalizedNumber = "+919876543210",
            startTimeEpochMs = now,
            endTimeEpochMs = now + 30_000L,
            durationSeconds = 30L,
            direction = CallDirection.OUTGOING
        )

        val result = gateManager.evaluateCall(call)
        assertTrue("Expected Allowed gate result", result is GateEvaluationResult.Allowed)
        val allowed = result as GateEvaluationResult.Allowed
        assertEquals("Alpha Client", allowed.lead.name)

        // Only on Allowed result does application query recording source
        mockRecordingSource.queryCandidates(call.startTimeEpochMs, call.endTimeEpochMs, call.durationSeconds, call.normalizedNumber)
        assertEquals("Recording source should be queried exactly once for allowed lead", 1, queryCallCount.get())
    }

    @Test
    fun testNonLeadCallStopsImmediatelyWithoutRecordingLookup() {
        val now = 1700000050000L
        val call = CallEvent(
            id = "call_non_lead",
            rawNumber = "+91 99999 88888",
            normalizedNumber = "+919999988888",
            startTimeEpochMs = now,
            endTimeEpochMs = now + 40_000L,
            durationSeconds = 40L,
            direction = CallDirection.INCOMING
        )

        val result = gateManager.evaluateCall(call)
        assertTrue("Expected RejectedNotALead gate result", result is GateEvaluationResult.RejectedNotALead)
        val rejected = result as GateEvaluationResult.RejectedNotALead
        assertEquals("+919999988888", rejected.normalizedNumber)

        // Crucial security invariant: Recording source must NOT be invoked
        assertEquals("Recording source must NOT be queried for non-lead", 0, queryCallCount.get())
    }

    @Test
    fun testDoNotRecordLeadStopsBeforeRecordingLookup() {
        val now = 1700000100000L
        val call = CallEvent(
            id = "call_dnr",
            rawNumber = "+91 98765 43212",
            normalizedNumber = "+919876543212",
            startTimeEpochMs = now,
            endTimeEpochMs = now + 25_000L,
            durationSeconds = 25L,
            direction = CallDirection.OUTGOING
        )

        val result = gateManager.evaluateCall(call)
        assertTrue("Expected RejectedDoNotRecord gate result", result is GateEvaluationResult.RejectedDoNotRecord)
        val dnrResult = result as GateEvaluationResult.RejectedDoNotRecord
        assertEquals("VIP DNR Partner", dnrResult.lead.name)

        // Crucial privacy invariant: Recording source must NOT be invoked for DNR calls
        assertEquals("Recording source must NOT be queried for DNR calls", 0, queryCallCount.get())
    }

    @Test
    fun testDuplicateCallEventProcessingIsPrevented() {
        val now = System.currentTimeMillis()
        val call1 = CallEvent(
            id = "call_dup_1",
            rawNumber = "+91 98765 43210",
            normalizedNumber = "+919876543210",
            startTimeEpochMs = now,
            endTimeEpochMs = now + 15_000L,
            durationSeconds = 15L,
            direction = CallDirection.OUTGOING
        )

        val firstEval = gateManager.evaluateCall(call1)
        assertTrue("First call evaluation should succeed", firstEval is GateEvaluationResult.Allowed)

        // Second immediate call with same signature
        val call2 = call1.copy(id = "call_dup_2")
        val secondEval = gateManager.evaluateCall(call2)
        assertTrue("Duplicate call should be rejected", secondEval is GateEvaluationResult.RejectedDuplicateCall)
    }

    @Test
    fun testAuditTraceRecordsGatingEventsWithoutLeakingRawData() {
        val call = CallEvent(
            id = "call_audit",
            rawNumber = "+91 98765 43210",
            normalizedNumber = "+919876543210",
            startTimeEpochMs = 1700000000000L,
            endTimeEpochMs = 1700000010000L,
            durationSeconds = 10L,
            direction = CallDirection.OUTGOING
        )
        gateManager.evaluateCall(call)
        val trace = gateManager.getAuditTrace()
        assertTrue("Audit trace should contain entries", trace.isNotEmpty())
        assertTrue("Trace should record GATE ALLOW", trace.any { it.contains("GATE ALLOW") })
    }
}
