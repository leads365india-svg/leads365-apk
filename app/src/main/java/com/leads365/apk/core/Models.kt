package com.leads365.apk.core

import android.net.Uri

enum class CallDirection {
    INCOMING,
    OUTGOING,
    MISSED,
    UNKNOWN
}

data class CallEvent(
    val id: String,
    val rawNumber: String,
    val normalizedNumber: String,
    val startTimeEpochMs: Long,
    val endTimeEpochMs: Long,
    val durationSeconds: Long,
    val direction: CallDirection,
    val simId: String? = null,
    val callType: Int? = null
)

data class TestLead(
    val id: String,
    val name: String,
    val rawNumber: String,
    val normalizedNumber: String,
    val isDoNotRecord: Boolean = false,
    val notes: String = "",
    val enabled: Boolean = true
)

sealed class GateEvaluationResult {
    data class Allowed(val lead: TestLead, val callEvent: CallEvent) : GateEvaluationResult()
    data class RejectedNotALead(val normalizedNumber: String, val reason: String) : GateEvaluationResult()
    data class RejectedDoNotRecord(val lead: TestLead, val reason: String) : GateEvaluationResult()
    data class RejectedDuplicateCall(val callEvent: CallEvent, val reason: String) : GateEvaluationResult()
}

enum class RecordingDiscoveryMethod {
    STORAGE_ACCESS_FRAMEWORK,
    MEDIA_STORE
}

data class RecordingCandidate(
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
    val mimeType: String,
    val durationSeconds: Long? = null,
    val sourceMethod: RecordingDiscoveryMethod,
    val relativePath: String? = null,
    val isReadable: Boolean = true,
    val unreadableReason: String? = null
) {
    val uri: Uri get() = Uri.parse(uriString)
}

enum class MatchStatus {
    FILE_FOUND,
    NO_FILE_FOUND,
    AMBIGUOUS,
    INACCESSIBLE
}

data class RecordingMatchResult(
    val status: MatchStatus,
    val matchedCandidate: RecordingCandidate? = null,
    val candidateCount: Int = 0,
    val confidenceScore: Double = 0.0,
    val reason: String = "",
    val evaluatedCandidates: List<CandidateScore> = emptyList()
)

data class CandidateScore(
    val candidate: RecordingCandidate,
    val score: Double,
    val breakdown: String
)

sealed class UploadState {
    object Idle : UploadState()
    data class ConfirmationRequired(val candidate: RecordingCandidate, val checksumSha256: String) : UploadState()
    data class Uploading(val progressPercent: Int, val bytesSent: Long, val totalBytes: Long) : UploadState()
    data class Success(val httpStatusCode: Int, val responseMessage: String, val timestampEpochMs: Long) : UploadState()
    data class Failed(val errorMessage: String, val isRetryable: Boolean) : UploadState()
}

enum class CompatibilityStatus {
    NOT_TESTED,
    NEEDS_ATTENTION,
    PARTIALLY_VERIFIED,
    VERIFIED
}

enum class PermissionStatus {
    GRANTED,
    NOT_GRANTED,
    RESTRICTED,
    UNAVAILABLE,
    ERROR,
    DENIED,
    NOT_REQUESTED
}

data class PermissionItem(
    val id: String,
    val title: String,
    val rationale: String,
    val status: PermissionStatus,
    val isMandatory: Boolean
)

data class DiagnosticsReport(
    val deviceModel: String,
    val manufacturer: String,
    val androidVersion: String,
    val sdkInt: Int,
    val safConfigured: Boolean,
    val safTreeUriValid: Boolean,
    val mediaStoreAudioAccessible: Boolean,
    val phoneStatePermissionGranted: Boolean,
    val callLogPermissionGranted: Boolean,
    val totalTestLeadsConfigured: Int,
    val lastGateResult: String?,
    val lastMatchingStatus: String?,
    val lastDiscoveryDelaySec: Long?,
    val overallCompatibility: CompatibilityStatus,
    val reportTimestampEpochMs: Long
)
