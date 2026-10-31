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
import com.leads365.apk.network.auth.TokenProvider
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
    private val tokenProvider: TokenProvider? = null,
    private val autoLeadCaptureConfig: AutoLeadCaptureConfig? = null,
    @Suppress("UNUSED_PARAMETER") dummy: Unit = Unit
) {

    @Inject
    constructor(
        leadRepository: LeadRepository,
        callRepository: CallRepository,
        pendingCallManager: PendingCallManager,
        normalizer: PhoneNumberNormalizer,
        recordingDiscoveryCoordinator: RecordingDiscoveryCoordinator,
        tokenProvider: TokenProvider,
        autoLeadCaptureConfig: AutoLeadCaptureConfig
    ) : this(
        leadRepository = leadRepository,
        callRepository = callRepository,
        pendingCallManager = pendingCallManager,
        normalizer = normalizer,
        recordingDiscoveryCoordinator = recordingDiscoveryCoordinator,
        tokenProvider = tokenProvider,
        autoLeadCaptureConfig = autoLeadCaptureConfig,
        dummy = Unit
    )

    constructor(
        leadRepository: LeadRepository,
        callRepository: CallRepository,
        pendingCallManager: PendingCallManager,
        normalizer: PhoneNumberNormalizer,
        recordingDiscoveryCoordinator: RecordingDiscoveryCoordinator,
        tokenProvider: TokenProvider
    ) : this(
        leadRepository = leadRepository,
        callRepository = callRepository,
        pendingCallManager = pendingCallManager,
        normalizer = normalizer,
        recordingDiscoveryCoordinator = recordingDiscoveryCoordinator,
        tokenProvider = tokenProvider,
        autoLeadCaptureConfig = null,
        dummy = Unit
    )

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
        tokenProvider = null,
        autoLeadCaptureConfig = null,
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
        tokenProvider = null,
        autoLeadCaptureConfig = null,
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

        // Fallback: Automatic Lead Capture for Answered Incoming Calls
        val phoneDigits = normalizer.extractLast10Digits(normalizedNumber).ifEmpty {
            normalizedNumber.replace("[^0-9]".toRegex(), "")
        }
        val isAutoEnabled = autoLeadCaptureConfig?.isAutoLeadCreationEnabled() == true
        val minDuration = autoLeadCaptureConfig?.getMinDurationSeconds() ?: AutoLeadCaptureConfig.DEFAULT_MIN_DURATION_SECONDS
        val isValidCallerNumber = phoneDigits.length >= 7 &&
            !normalizedNumber.contains("unknown", ignoreCase = true) &&
            !normalizedNumber.contains("private", ignoreCase = true)
        val isNativeIncomingType = callEvent.callType == null || callEvent.callType == CallLog.Calls.INCOMING_TYPE
        val isNotMissedOrRejected = callEvent.direction != CallDirection.MISSED &&
            callEvent.callType != CallLog.Calls.MISSED_TYPE &&
            callEvent.callType != CallLog.Calls.REJECTED_TYPE

        if (matchingLead == null &&
            isAutoEnabled &&
            callEvent.direction == CallDirection.INCOMING &&
            isNativeIncomingType &&
            isNotMissedOrRejected &&
            callEvent.durationSeconds >= minDuration &&
            isValidCallerNumber
        ) {
            // Check Do-Not-Record (DNR) policy before auto-creating lead
            val isDnr = try {
                val allLeads = (leadRepository.getAllLeads() as? AppResult.Success)?.data.orEmpty()
                allLeads.any { it.isDoNotRecord && (it.normalizedNumber == normalizedNumber || normalizer.extractLast10Digits(it.normalizedNumber) == phoneDigits) }
            } catch (e: Exception) {
                false
            }

            if (!isDnr) {
                val autoLeadId = "lead_auto_${phoneDigits}_${callEvent.startTimeEpochMs}"
                val provisionalName = "Unknown Caller - $normalizedNumber"
                val autoLead = LeadEntity(
                    id = autoLeadId,
                    name = provisionalName,
                    rawNumber = callEvent.rawNumber,
                    normalizedNumber = normalizedNumber,
                    status = "New",
                    isDoNotRecord = false,
                    notes = "Auto-captured from answered incoming call (${callEvent.durationSeconds}s)",
                    createdAtEpochMs = callEvent.startTimeEpochMs,
                    updatedAtEpochMs = now
                )
                val saveRes = leadRepository.saveLead(autoLead)
                if (saveRes is AppResult.Success) {
                    matchingLead = autoLead
                    Log.i(TAG, "Auto-created provisional Lead for incoming call: id=$autoLeadId, phone=$redactedNumber, duration=${callEvent.durationSeconds}s")
                } else {
                    Log.w(TAG, "Failed to persist auto-created Lead: $saveRes")
                }
            } else {
                Log.i(TAG, "Skipping auto-lead creation: number $redactedNumber matches Do-Not-Record (DNR) policy.")
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

        // 6. Persist Eligible Call to Room with canonical deterministic clientCallId
        val rawNativeId = callEvent.id.trim()
        val installId = tokenProvider?.getDeviceIdOrInstallId()?.trim()?.ifEmpty { null }
        val tenantId = tokenProvider?.getAgencyAccountId()?.trim()?.ifEmpty { null }

        val hasRequiredIdentity = !tenantId.isNullOrBlank() && !installId.isNullOrBlank()

        val clientCallId = if (hasRequiredIdentity) {
            if (rawNativeId.isNotBlank() && !rawNativeId.startsWith("call_")) {
                "call_${tenantId}_${installId}_$rawNativeId"
            } else {
                "call_${tenantId}_${installId}_${phoneDigits}_${callEvent.startTimeEpochMs}"
            }
        } else {
            // Unsafe identity placeholders like "dev" or "default" are NEVER used.
            // Safely defer call sync until identity is available while preserving the call locally.
            val localKey = if (rawNativeId.isNotBlank() && !rawNativeId.startsWith("call_")) {
                rawNativeId
            } else {
                "${phoneDigits}_${callEvent.startTimeEpochMs}"
            }
            "local_pending_call_$localKey"
        }

        val syncStatus = if (hasRequiredIdentity) "PENDING" else "DEFERRED_IDENTITY"

        val callType = callEvent.callType ?: when (callEvent.direction) {
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
            notes = if (matchingLead?.id?.startsWith("lead_auto_") == true) {
                matchingLead.notes
            } else if (isUnlinkedInbound) {
                "Unlinked incoming call (Website Call attribution unverified)"
            } else {
                null
            },
            simId = callEvent.simId,
            callOutcome = initialOutcome,
            syncStatus = syncStatus,
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
