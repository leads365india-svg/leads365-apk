package com.leads365.apk.recordings

import android.content.Context
import android.util.Log
import com.leads365.apk.core.CallDirection
import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.MatchStatus
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.core.PrivacyRedactor
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.RecordingMatchResult
import com.leads365.apk.data.repository.CallRepository
import com.leads365.apk.data.repository.UploadQueueRepository
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.work.RecordingUploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
sealed class DiscoveryResult {
    data class Matched(
        val candidate: RecordingCandidate,
        val matchResult: RecordingMatchResult,
        val attemptsUsed: Int
    ) : DiscoveryResult()

    data class Ambiguous(
        val matchResult: RecordingMatchResult,
        val candidates: List<RecordingCandidate>,
        val attemptsUsed: Int
    ) : DiscoveryResult()

    data class NoFileFound(
        val reason: String,
        val attemptsUsed: Int
    ) : DiscoveryResult()

    data class Inaccessible(
        val reason: String,
        val candidate: RecordingCandidate?,
        val attemptsUsed: Int
    ) : DiscoveryResult()

    data class GatedNotLead(val redactedNumber: String) : DiscoveryResult()
    data class GatedDoNotRecord(val leadId: String) : DiscoveryResult()
    data class Error(val message: String) : DiscoveryResult()
}

@Singleton
class RecordingDiscoveryCoordinator(
    private val safRecordingSource: SafRecordingSource,
    private val mediaStoreRecordingSource: MediaStoreRecordingSource,
    private val matcher: TargetedRecordingMatcher,
    private val callRepository: CallRepository,
    private val uploadQueueRepository: UploadQueueRepository,
    private val normalizer: PhoneNumberNormalizer,
    delayProvider: suspend (Long) -> Unit = { delay(it) },
    private val context: Context? = null
) {
    var delayProvider: suspend (Long) -> Unit = delayProvider

    @Inject
    constructor(
        @ApplicationContext context: Context,
        safRecordingSource: SafRecordingSource,
        mediaStoreRecordingSource: MediaStoreRecordingSource,
        matcher: TargetedRecordingMatcher,
        callRepository: CallRepository,
        uploadQueueRepository: UploadQueueRepository,
        normalizer: PhoneNumberNormalizer
    ) : this(
        safRecordingSource = safRecordingSource,
        mediaStoreRecordingSource = mediaStoreRecordingSource,
        matcher = matcher,
        callRepository = callRepository,
        uploadQueueRepository = uploadQueueRepository,
        normalizer = normalizer,
        delayProvider = { delay(it) },
        context = context
    )

    /**
     * Non-negotiable Phase 7 Critical Path:
     * Privacy Gates -> Targeted Recording Discovery -> Reliable Matching -> Bounded Retries -> Accurate Result States
     */
    suspend fun discoverAndMatchRecording(
        callEvent: CallEvent,
        lead: LeadEntity?,
        retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
        callId: String = callEvent.id
    ): DiscoveryResult {
        val normalizedNumber = normalizer.normalizeToE164(callEvent.normalizedNumber)
            ?: normalizer.normalizeToE164(callEvent.rawNumber)
            ?: callEvent.normalizedNumber
        val redactedNumber = PrivacyRedactor.redactPhoneNumber(normalizedNumber)

        // 1. PRIVACY GATE: Assigned Lead Only OR Eligible Inbound Call
        val isEligibleInbound = lead == null && (callEvent.direction == CallDirection.INCOMING || callEvent.direction == CallDirection.MISSED)
        if (lead == null && !isEligibleInbound) {
            Log.i(TAG, "PRIVACY GATE: Call with $redactedNumber is not an assigned lead and not an eligible inbound call. Halting recording discovery.")
            return DiscoveryResult.GatedNotLead(redactedNumber)
        }

        // 2. PRIVACY GATE: Do-Not-Record (DNR) Enforcement
        if (lead?.isDoNotRecord == true) {
            Log.i(TAG, "PRIVACY GATE: Lead id=${lead.id} ($redactedNumber) has DNR enabled. Halting recording discovery.")
            return DiscoveryResult.GatedDoNotRecord(lead.id)
        }

        val targetCallId = if (callId.isNotBlank()) callId else callEvent.id
        Log.d(TAG, "Starting targeted recording discovery for callId=$targetCallId, leadId=${lead?.id ?: "unlinked"}")

        var attempts = 0
        var candidates: List<RecordingCandidate>
        var matchResult: RecordingMatchResult

        // Initial scan
        attempts++
        candidates = scanSourcesForCandidates(callEvent, normalizedNumber)
        matchResult = matcher.match(candidates, callEvent)

        // Bounded retries for delayed OEM dialer writes (Vivo, Xiaomi, Samsung)
        if (matchResult.status == MatchStatus.NO_FILE_FOUND && retryDelaysMs.isNotEmpty()) {
            for (delayMs in retryDelaysMs) {
                if (delayMs > 0) {
                    delayProvider(delayMs)
                }
                attempts++
                candidates = scanSourcesForCandidates(callEvent, normalizedNumber)
                matchResult = matcher.match(candidates, callEvent)
                if (matchResult.status != MatchStatus.NO_FILE_FOUND) {
                    break
                }
            }
        }

        return when (matchResult.status) {
            MatchStatus.FILE_FOUND -> {
                val matched = matchResult.matchedCandidate!!
                Log.i(TAG, "RECORDING MATCH CONFIRMED: file=${matched.displayName}, confidence=${(matchResult.confidenceScore * 100).toInt()}% (attempt $attempts)")

                // Update Room CallEntity
                callRepository.updateRecordingMatch(
                    callId = targetCallId,
                    hasRecording = true,
                    recordingUri = matched.uriString,
                    recordingFileName = matched.displayName,
                    recordingFileSizeBytes = matched.sizeBytes
                )

                // Enqueue upload task for Phase 8
                val uploadTask = UploadTaskEntity(
                    id = UUID.randomUUID().toString(),
                    callId = targetCallId,
                    leadId = lead?.id ?: "unlinked",
                    localFileUri = matched.uriString,
                    fileName = matched.displayName,
                    fileSizeBytes = matched.sizeBytes,
                    status = UploadTaskEntity.STATUS_PENDING
                )
                uploadQueueRepository.enqueueTask(uploadTask)

                if (context != null) {
                    try {
                        RecordingUploadWorker.enqueueUniqueUpload(context, uploadTask.id, targetCallId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to enqueue RecordingUploadWorker: ${e.message}")
                    }
                }

                DiscoveryResult.Matched(matched, matchResult, attempts)
            }
            MatchStatus.AMBIGUOUS -> {
                Log.w(TAG, "AMBIGUOUS MATCH: Multiple candidates scored within threshold. Gating to prevent false association. (attempt $attempts)")
                callRepository.updateRecordingMatch(
                    callId = targetCallId,
                    hasRecording = false,
                    recordingUri = null,
                    recordingFileName = null,
                    recordingFileSizeBytes = null
                )
                DiscoveryResult.Ambiguous(matchResult, candidates, attempts)
            }
            MatchStatus.NO_FILE_FOUND -> {
                val reportedReason = if (callEvent.direction == CallDirection.INCOMING || isEligibleInbound) {
                    "Incoming call detected — recording not found"
                } else {
                    matchResult.reason
                }
                Log.i(TAG, "NO RECORDING FOUND: $reportedReason (attempts: $attempts)")
                callRepository.updateRecordingMatch(
                    callId = targetCallId,
                    hasRecording = false,
                    recordingUri = null,
                    recordingFileName = null,
                    recordingFileSizeBytes = null
                )
                DiscoveryResult.NoFileFound(reportedReason, attempts)
            }
            MatchStatus.INACCESSIBLE -> {
                Log.w(TAG, "INACCESSIBLE RECORDING: ${matchResult.reason} (attempts: $attempts)")
                callRepository.updateRecordingMatch(
                    callId = targetCallId,
                    hasRecording = false,
                    recordingUri = null,
                    recordingFileName = null,
                    recordingFileSizeBytes = null
                )
                DiscoveryResult.Inaccessible(matchResult.reason, matchResult.matchedCandidate, attempts)
            }
        }
    }

    fun scanSourcesForCandidates(callEvent: CallEvent, normalizedNumber: String): List<RecordingCandidate> {
        val result = mutableListOf<RecordingCandidate>()
        val seenUris = mutableSetOf<String>()

        // 1. Storage Access Framework (SAF)
        if (safRecordingSource.isConfigured() && safRecordingSource.isAccessible()) {
            try {
                val safCandidates = safRecordingSource.queryCandidates(
                    callStartEpochMs = callEvent.startTimeEpochMs,
                    callEndEpochMs = callEvent.endTimeEpochMs,
                    callDurationSec = callEvent.durationSeconds,
                    normalizedNumber = normalizedNumber
                )
                for (c in safCandidates) {
                    if (seenUris.add(c.uriString)) {
                        result.add(c)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying SAF recording source: ${e.message}")
            }
        }

        // 2. MediaStore
        if (mediaStoreRecordingSource.isAccessible()) {
            try {
                val mediaStoreCandidates = mediaStoreRecordingSource.queryCandidates(
                    callStartEpochMs = callEvent.startTimeEpochMs,
                    callEndEpochMs = callEvent.endTimeEpochMs,
                    callDurationSec = callEvent.durationSeconds,
                    normalizedNumber = normalizedNumber
                )
                for (c in mediaStoreCandidates) {
                    if (seenUris.add(c.uriString)) {
                        result.add(c)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying MediaStore recording source: ${e.message}")
            }
        }

        return result
    }

    companion object {
        private const val TAG = "RecordingDiscovery"
        val DEFAULT_RETRY_DELAYS_MS = listOf(1500L, 3000L, 6000L, 10000L)
    }
}
