package com.leads365.apk.calls

import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.GateEvaluationResult
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.core.PrivacyRedactor
import com.leads365.apk.core.TestLead

class CallGateManager(
    private val normalizer: PhoneNumberNormalizer = PhoneNumberNormalizer(),
    initialLeads: List<TestLead> = emptyList()
) {

    private val testLeads = mutableListOf<TestLead>().apply { addAll(initialLeads) }
    private val processedCallSignatures = mutableMapOf<String, Long>()
    private val auditTrace = mutableListOf<String>()

    fun getTestLeads(): List<TestLead> = testLeads.toList()

    fun setLeads(leads: List<TestLead>) {
        testLeads.clear()
        testLeads.addAll(leads)
        recordTrace("Updated test leads list (count=${leads.size})")
    }

    fun addTestLead(lead: TestLead) {
        testLeads.removeAll { it.id == lead.id }
        testLeads.add(0, lead)
        recordTrace("Added test lead: ${lead.name} (${PrivacyRedactor.redactPhoneNumber(lead.normalizedNumber)})")
    }

    fun removeTestLead(leadId: String) {
        val removed = testLeads.find { it.id == leadId }
        testLeads.removeAll { it.id == leadId }
        if (removed != null) {
            recordTrace("Removed test lead id=$leadId (${PrivacyRedactor.redactPhoneNumber(removed.normalizedNumber)})")
        }
    }

    fun toggleDoNotRecord(leadId: String, dnr: Boolean) {
        val index = testLeads.indexOfFirst { it.id == leadId }
        if (index >= 0) {
            val updated = testLeads[index].copy(isDoNotRecord = dnr)
            testLeads[index] = updated
            recordTrace("Toggled DNR=$dnr for lead id=$leadId (${PrivacyRedactor.redactPhoneNumber(updated.normalizedNumber)})")
        }
    }

    /**
     * Non-negotiable Lead-Only Gate evaluation.
     * Must be evaluated BEFORE any recording folder scan or MediaStore query.
     */
    fun evaluateCall(callEvent: CallEvent): GateEvaluationResult {
        val callSignature = "${callEvent.normalizedNumber}_${callEvent.startTimeEpochMs}"
        val now = System.currentTimeMillis()

        // 1. Deduplication check
        val lastSeen = processedCallSignatures[callSignature]
        if (lastSeen != null && (now - lastSeen) < DUPLICATE_WINDOW_MS) {
            val result = GateEvaluationResult.RejectedDuplicateCall(
                callEvent,
                "Duplicate call event detected within ${DUPLICATE_WINDOW_MS / 1000}s window"
            )
            recordTrace("GATE REJECT (Duplicate): signature=$callSignature")
            return result
        }
        processedCallSignatures[callSignature] = now

        // Cleanup old signatures
        processedCallSignatures.entries.removeIf { (now - it.value) > DUPLICATE_RETENTION_MS }

        // 2. Normalization
        val normalizedIncoming = normalizer.normalizeToE164(callEvent.normalizedNumber)
            ?: normalizer.normalizeToE164(callEvent.rawNumber)
            ?: callEvent.normalizedNumber

        val incomingLast10 = normalizer.extractLast10Digits(normalizedIncoming)

        // 3. Lead Allowlist check
        val matchingLead = testLeads.firstOrNull { lead ->
            if (!lead.enabled) return@firstOrNull false
            val leadNorm = lead.normalizedNumber
            val leadLast10 = normalizer.extractLast10Digits(leadNorm)

            leadNorm == normalizedIncoming ||
                    (incomingLast10.isNotEmpty() && incomingLast10 == leadLast10)
        }

        if (matchingLead == null) {
            val result = GateEvaluationResult.RejectedNotALead(
                normalizedNumber = normalizedIncoming,
                reason = "Call number does not match any configured active test lead. Halting before recording lookup."
            )
            recordTrace("GATE REJECT (Not a lead): ${PrivacyRedactor.redactPhoneNumber(normalizedIncoming)}")
            return result
        }

        // 4. Do-Not-Record (DNR) check
        if (matchingLead.isDoNotRecord) {
            val result = GateEvaluationResult.RejectedDoNotRecord(
                lead = matchingLead,
                reason = "Matched lead is marked Do-Not-Record. Halting before recording lookup."
            )
            recordTrace("GATE REJECT (Do-Not-Record): lead=${matchingLead.name} (${PrivacyRedactor.redactPhoneNumber(matchingLead.normalizedNumber)})")
            return result
        }

        // 5. Allow
        val result = GateEvaluationResult.Allowed(matchingLead, callEvent)
        recordTrace("GATE ALLOW: lead=${matchingLead.name} (${PrivacyRedactor.redactPhoneNumber(matchingLead.normalizedNumber)})")
        return result
    }

    fun getAuditTrace(): List<String> = auditTrace.toList()

    private fun recordTrace(entry: String) {
        auditTrace.add("[${System.currentTimeMillis()}] $entry")
        if (auditTrace.size > 200) {
            auditTrace.removeAt(0)
        }
    }

    companion object {
        const val DUPLICATE_WINDOW_MS = 15_000L
        const val DUPLICATE_RETENTION_MS = 600_000L

        fun defaultTestLeads(): List<TestLead> {
            val norm = PhoneNumberNormalizer()
            return listOf(
                TestLead(
                    id = "lead_test_01",
                    name = "Acme Realty Test Lead",
                    rawNumber = "+91 98765 43210",
                    normalizedNumber = norm.normalizeToE164("+919876543210") ?: "+919876543210",
                    isDoNotRecord = false,
                    notes = "Primary outbound test lead"
                ),
                TestLead(
                    id = "lead_test_02",
                    name = "TechCorp Test Inbound",
                    rawNumber = "09876543211",
                    normalizedNumber = norm.normalizeToE164("09876543211") ?: "+919876543211",
                    isDoNotRecord = false,
                    notes = "Secondary test lead (leading zero format)"
                ),
                TestLead(
                    id = "lead_test_03_dnr",
                    name = "VIP Partner (Do-Not-Record)",
                    rawNumber = "+91 98765 43212",
                    normalizedNumber = norm.normalizeToE164("+919876543212") ?: "+919876543212",
                    isDoNotRecord = true,
                    notes = "Strictly non-recordable test fixture"
                )
            )
        }
    }
}
