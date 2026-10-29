package com.leads365.apk.calls

import android.provider.CallLog
import android.util.Log
import com.leads365.apk.BuildConfig
import com.leads365.apk.core.AppResult
import com.leads365.apk.core.CallDirection
import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.core.PrivacyRedactor
import com.leads365.apk.data.repository.CallRepository
import com.leads365.apk.data.repository.LeadRepository
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.recordings.DiscoveryResult
import com.leads365.apk.recordings.RecordingDiscoveryCoordinator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed class CallProcessingResult {
    data class Recorded(
        val call: CallEntity,
        val lead: LeadEntity,
        val discoveryResult: DiscoveryResult? = null
    ) : CallProcessingResult()
    data class UnlinkedIncomingRecorded(
        val call: CallEntity,
        val discoveryResult: DiscoveryResult? = null
    ) : CallProcessingResult()
    data class DuplicateIgnored(val existingCall: CallEntity) : CallProcessingResult()
    data class RejectedNotALead(val redactedNumber: String) : CallProcessingResult()
    data class RejectedDoNotRecord(val lead: LeadEntity) : CallProcessingResult()
    data class Error(val message: String) : CallProcessingResult()
}

@Singleton
class CallProcessingManager(
    private val leadRepository: LeadRepository,
    private val callRepository: CallRepository,
    private val pendingCallManager: PendingCallManager,
    private val normalizer: PhoneNumberNormalizer,
    private val recordingDiscoveryCoordinator: RecordingDiscoveryCoordinator?,
    @Suppress("UNUSED_PARAMETER") dummy: Unit = Unit
) {

    @Inject
    constructor(
        leadRepository: LeadRepository,
        callRepository: CallRepository,
        pendingCallManager: PendingCallManager,
        normalizer: PhoneNumberNormalizer,
        recordingDiscoveryCoordinator: RecordingDiscoveryCoordinator
    ) : this(
        leadRepository = leadRepository,
        callRepository = callRepository,
        pendingCallManager = pendingCallManager,
        normalizer = normalizer,
        recordingDiscoveryCoordinator = recordingDiscoveryCoordinator,
        dummy = Unit
    )

    constructor(
        leadRepository: LeadRepository,
        callRepository: CallRepository,
        pendingCallManager: PendingCallManager,
        normalizer: PhoneNumberNormalizer
    ) : this(
        leadRepository = leadRepository,
        callRepository = callRepository,
        pendingCallManager = pendingCallManager,
        normalizer = normalizer,
        recordingDiscoveryCoordinator = null,
        dummy = Unit
    )

    private val processedSignatures = ConcurrentHashMap<String, Long>()

    private val _completedLeadCalls = MutableSharedFlow<Pair<CallEntity, LeadEntity>>(extraBufferCapacity = 10)
    val completedLeadCalls: SharedFlow<Pair<CallEntity, LeadEntity>> = _completedLeadCalls.asSharedFlow()

    /**
     * Non-negotiable call processing pipeline:
     * 1. Deduplication (in-memory signature and Room database query).
     * 2. Number normalization.
     * 3. Lead lookup against Room database (and pending call context).
     * 4. Assigned-lead gate: non-leads halted immediately.
     * 5. DNR gate: Do-Not-Record leads halted immediately.
     * 6. Eligible call persisted to Room with unique clientCallId UUID.
     */
    suspend fun processCallEvent(callEvent: CallEvent): CallProcessingResult {
        val now = System.currentTimeMillis()
        val normalizedNumber = normalizer.normalizeToE164(callEvent.normalizedNumber)
            ?: normalizer.normalizeToE164(callEvent.rawNumber)
            ?: callEvent.normalizedNumber

        val redactedNumber = PrivacyRedactor.redactPhoneNumber(normalizedNumber)

        // 1. In-memory deduplication check
        val memorySignature = "${normalizedNumber}_${callEvent.startTimeEpochMs}"
        val lastSeenMemory = processedSignatures[memorySignature]
        if (lastSeenMemory != null && (now - lastSeenMemory) < DEDUPLICATION_WINDOW_MS) {
            Log.d(TAG, "Duplicate call ignored (memory): signature=$memorySignature")
            val existing = (callRepository.findCallBySignature(normalizedNumber, callEvent.startTimeEpochMs, 5000L) as? AppResult.Success)?.data
            return CallProcessingResult.DuplicateIgnored(existing ?: createStubEntity(callEvent, normalizedNumber))
        }

        // 2. Database deduplication check (+/- 5000ms window for timestamp jitter)
        val existingDbCall = when (val res = callRepository.findCallBySignature(normalizedNumber, callEvent.startTimeEpochMs, 5000L)) {
            is AppResult.Success -> res.data
            else -> null
        }
        if (existingDbCall != null) {
            Log.d(TAG, "Duplicate call ignored (database): callId=${existingDbCall.clientCallId}")
            processedSignatures[memorySignature] = now
            return CallProcessingResult.DuplicateIgnored(existingDbCall)
        }

        // 3. Resolve Assigned Lead
        var matchingLead: LeadEntity? = when (val leadRes = leadRepository.getLeadByNormalizedNumber(normalizedNumber)) {
            is AppResult.Success -> leadRes.data
            else -> null
        }

        // Fallback: check last 10 digits across normalized and raw number fields
        if (matchingLead == null) {
            val incomingLast10 = normalizer.extractLast10Digits(normalizedNumber).ifEmpty {
                normalizer.extractLast10Digits(callEvent.rawNumber)
            }
            if (incomingLast10.isNotEmpty()) {
                val allLeads = (leadRepository.getAllLeads() as? AppResult.Success)?.data.orEmpty()
                matchingLead = allLeads.firstOrNull { lead ->
                    val norm10 = normalizer.extractLast10Digits(lead.normalizedNumber)
                    val raw10 = normalizer.extractLast10Digits(lead.rawNumber)
                    (norm10.isNotEmpty() && norm10 == incomingLast10) ||
                    (raw10.isNotEmpty() && raw10 == incomingLast10)
                }
            }
        }

        // If outgoing call, also verify against pending call context
        val pendingContext = pendingCallManager.consumePendingCallForNumber(normalizedNumber)
        if (matchingLead == null && pendingContext != null) {
            matchingLead = (leadRepository.getLeadById(pendingContext.leadId) as? AppResult.Success)?.data
        }

        // Fallback: Check wizard test leads and persist to local Room cache (DEBUG/QA builds only)
        if (matchingLead == null && BuildConfig.DEBUG) {
            val testLead = pendingCallManager.findTestLead(normalizedNumber)
            if (testLead != null) {
                Log.i(TAG, "Resolved call to wizard test lead (DEBUG only): id=${testLead.id}, name=${testLead.name}. Syncing to Room database.")
                leadRepository.saveLead(testLead)
                matchingLead = testLead
            }
        }

        // 4. Assigned-Lead Gate & Unlinked Incoming Customer Detection
        val isUnlinkedInbound = matchingLead == null && (callEvent.direction == CallDirection.INCOMING || callEvent.direction == CallDirection.MISSED)

        if (matchingLead == null && !isUnlinkedInbound) {
            Log.i(TAG, "GATE REJECT: Number $redactedNumber is not an assigned lead. Halting call recording pipeline.")
            processedSignatures[memorySignature] = now
            cleanupOldSignatures(now)
            return CallProcessingResult.RejectedNotALead(redactedNumber)
        }

        // 5. Do-Not-Record (DNR) Gate
        if (matchingLead != null && matchingLead.isDoNotRecord) {
            Log.i(TAG, "GATE REJECT: Lead id=${matchingLead.id} ($redactedNumber) is marked Do-Not-Record. Halting before recording lookup.")
            processedSignatures[memorySignature] = now
            cleanupOldSignatures(now)
            return CallProcessingResult.RejectedDoNotRecord(matchingLead)
        }

        // 6. Persist Eligible Call to Room
        val clientCallId = UUID.randomUUID().toString()
        val callType = when (callEvent.direction) {
            CallDirection.INCOMING -> CallLog.Calls.INCOMING_TYPE
            CallDirection.OUTGOING -> CallLog.Calls.OUTGOING_TYPE
            CallDirection.MISSED -> CallLog.Calls.MISSED_TYPE
            else -> if (isUnlinkedInbound) CallLog.Calls.INCOMING_TYPE else CallLog.Calls.OUTGOING_TYPE
        }

        val initialOutcome = if (callEvent.direction == CallDirection.MISSED) "Missed" else null

        val callEntity = CallEntity(
            id = clientCallId,
            clientCallId = clientCallId,
            normalizedNumber = normalizedNumber,
            rawNumber = callEvent.rawNumber,
            callType = callType,
            startTimeEpochMs = callEvent.startTimeEpochMs,
            durationSeconds = callEvent.durationSeconds,
            isLeadCall = matchingLead != null,
            leadId = matchingLead?.id,
            notes = if (isUnlinkedInbound) "Unlinked incoming call (Website Call attribution unverified)" else null,
            simId = callEvent.simId,
            callOutcome = initialOutcome,
            syncStatus = "PENDING",
            hasRecording = false
        )

        val saveResult = callRepository.recordCall(callEntity)
        if (saveResult is AppResult.Failure) {
            Log.e(TAG, "Failed to persist call record to Room: ${saveResult.error.message}")
            return CallProcessingResult.Error("Database error persisting call: ${saveResult.error.message}")
        }

        processedSignatures[memorySignature] = now
        cleanupOldSignatures(now)

        Log.i(TAG, "Eligible call persisted: clientCallId=$clientCallId, isUnlinked=$isUnlinkedInbound, leadId=${matchingLead?.id}, direction=${callEvent.direction}, duration=${callEvent.durationSeconds}s")

        // Phase 7: Trigger targeted recording discovery if coordinator is configured
        val discoveryResult = recordingDiscoveryCoordinator?.discoverAndMatchRecording(
            callEvent = callEvent,
            lead = matchingLead,
            callId = clientCallId
        )

        val finalCallEntity = if (discoveryResult is DiscoveryResult.Matched) {
            val updated = callEntity.copy(
                hasRecording = true,
                recordingUri = discoveryResult.candidate.uriString,
                recordingFileName = discoveryResult.candidate.displayName,
                recordingFileSizeBytes = discoveryResult.candidate.sizeBytes
            )
            callRepository.updateRecordingMatch(
                callId = clientCallId,
                hasRecording = true,
                recordingUri = discoveryResult.candidate.uriString,
                recordingFileName = discoveryResult.candidate.displayName,
                recordingFileSizeBytes = discoveryResult.candidate.sizeBytes
            )
            updated
        } else {
            callEntity
        }

        // Emit for Post-Call outcome prompt (if not missed and lead exists)
        if (callEvent.direction != CallDirection.MISSED && matchingLead != null) {
            _completedLeadCalls.tryEmit(Pair(finalCallEntity, matchingLead))
        }

        return if (matchingLead != null) {
            CallProcessingResult.Recorded(finalCallEntity, matchingLead, discoveryResult)
        } else {
            CallProcessingResult.UnlinkedIncomingRecorded(finalCallEntity, discoveryResult)
        }
    }

    private fun cleanupOldSignatures(now: Long) {
        processedSignatures.entries.removeIf { (now - it.value) > DEDUPLICATION_WINDOW_MS }
    }

    private fun createStubEntity(callEvent: CallEvent, normalizedNumber: String): CallEntity {
        return CallEntity(
            id = "stub_${callEvent.startTimeEpochMs}",
            clientCallId = "stub_${callEvent.startTimeEpochMs}",
            normalizedNumber = normalizedNumber,
            rawNumber = callEvent.rawNumber,
            callType = CallLog.Calls.OUTGOING_TYPE,
            startTimeEpochMs = callEvent.startTimeEpochMs,
            durationSeconds = callEvent.durationSeconds
        )
    }

    companion object {
        private const val TAG = "CallProcessingManager"
        private const val DEDUPLICATION_WINDOW_MS = 900_000L // 15 minutes
    }
}
