package com.leads365.apk.recordings

import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.CandidateScore
import com.leads365.apk.core.MatchStatus
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.RecordingMatchResult
import com.leads365.apk.core.TimeHelpers
import kotlin.math.abs
import kotlin.math.max

class TargetedRecordingMatcher(
    private val normalizer: PhoneNumberNormalizer = PhoneNumberNormalizer(),
    private val minAcceptanceScore: Double = 0.50,
    private val ambiguityDeltaThreshold: Double = 0.15
) {

    fun match(
        candidates: List<RecordingCandidate>,
        callEvent: CallEvent
    ): RecordingMatchResult {
        if (candidates.isEmpty()) {
            return RecordingMatchResult(
                status = MatchStatus.NO_FILE_FOUND,
                matchedCandidate = null,
                candidateCount = 0,
                confidenceScore = 0.0,
                reason = "No recording files detected within the bounded time window."
            )
        }

        val evaluated = candidates.map { candidate ->
            val (score, breakdown) = scoreCandidate(candidate, callEvent)
            CandidateScore(candidate, score, breakdown)
        }.sortedByDescending { it.score }

        // Filter those above minimum baseline
        val viable = evaluated.filter { it.score >= minAcceptanceScore }

        if (viable.isEmpty()) {
            return RecordingMatchResult(
                status = MatchStatus.NO_FILE_FOUND,
                matchedCandidate = null,
                candidateCount = candidates.size,
                confidenceScore = evaluated.firstOrNull()?.score ?: 0.0,
                reason = "Detected ${candidates.size} candidate(s), but none reached the minimum confidence threshold ($minAcceptanceScore).",
                evaluatedCandidates = evaluated
            )
        }

        // Filter viable candidates by readability
        val readableViable = viable.filter { it.candidate.isReadable }
        if (readableViable.isEmpty()) {
            val topUnreadable = viable.first()
            return RecordingMatchResult(
                status = MatchStatus.INACCESSIBLE,
                matchedCandidate = topUnreadable.candidate,
                candidateCount = candidates.size,
                confidenceScore = topUnreadable.score,
                reason = "Candidate '${topUnreadable.candidate.displayName}' matches the call parameters, but the audio file cannot be opened or read (${topUnreadable.candidate.unreadableReason ?: "inaccessible or 0-byte file"}).",
                evaluatedCandidates = evaluated
            )
        }

        // If only 1 readable viable candidate
        if (readableViable.size == 1) {
            val top = readableViable.first()
            return RecordingMatchResult(
                status = MatchStatus.FILE_FOUND,
                matchedCandidate = top.candidate,
                candidateCount = candidates.size,
                confidenceScore = top.score,
                reason = "Unique match confirmed with confidence ${(top.score * 100).toInt()}%. (${top.breakdown})",
                evaluatedCandidates = evaluated
            )
        }

        // Multiple readable viable candidates: check score gap
        val top = readableViable[0]
        val runnerUp = readableViable[1]
        val delta = top.score - runnerUp.score

        if (delta >= ambiguityDeltaThreshold) {
            return RecordingMatchResult(
                status = MatchStatus.FILE_FOUND,
                matchedCandidate = top.candidate,
                candidateCount = candidates.size,
                confidenceScore = top.score,
                reason = "Distinct top match identified (lead score: ${(top.score * 100).toInt()}%, runner-up: ${(runnerUp.score * 100).toInt()}%).",
                evaluatedCandidates = evaluated
            )
        } else {
            return RecordingMatchResult(
                status = MatchStatus.AMBIGUOUS,
                matchedCandidate = null,
                candidateCount = candidates.size,
                confidenceScore = top.score,
                reason = "Ambiguous match: top two candidate scores are too close (${(top.score * 100).toInt()}% vs ${(runnerUp.score * 100).toInt()}%). Gated to prevent false association.",
                evaluatedCandidates = evaluated
            )
        }
    }

    private fun scoreCandidate(candidate: RecordingCandidate, call: CallEvent): Pair<Double, String> {
        var breakdownDetails = ""

        // 1. Time Score (weight: 0.40)
        // Accommodates dialers setting timestamp at dialing/creation (callStart) or completion (callEnd)
        val fileTimeMs = candidate.lastModifiedEpochMs
        val callStartMs = call.startTimeEpochMs
        val callEndMs = call.endTimeEpochMs

        val timeScore = when {
            // File timestamped between callStart - 120s and callEnd + 90s: ideal dialer flush window
            fileTimeMs in (callStartMs - 120_000L)..(callEndMs + 90_000L) -> 0.40
            // File timestamped within 90s to 240s after call end:
            fileTimeMs in (callEndMs + 90_001L)..(callEndMs + 240_000L) -> 0.32
            // File timestamped within 240s to 600s after call end (10 min):
            fileTimeMs in (callEndMs + 240_001L)..(callEndMs + 600_000L) -> 0.22
            // File timestamped between 120s and 240s before call start (long ring):
            fileTimeMs in (callStartMs - 240_000L)..(callStartMs - 120_001L) -> 0.28
            // Fallback when lastModified is missing from SAF provider:
            fileTimeMs == 0L || fileTimeMs == callEndMs -> 0.35
            else -> 0.10
        }
        breakdownDetails += "Time: ${(timeScore * 100).toInt()}pts; "

        // 2. Duration Score (weight: 0.35)
        val durationScore = if (candidate.durationSeconds != null && call.durationSeconds > 0) {
            val fileDur = candidate.durationSeconds
            val callDur = call.durationSeconds
            val durDiff = abs(fileDur - callDur)

            when {
                durDiff <= 5 -> 0.35
                durDiff <= 10 -> 0.28
                durDiff <= 20 -> 0.18
                durDiff <= 45 -> 0.10
                else -> 0.05
            }
        } else {
            // If audio duration is not in metadata (e.g. SAF DocumentFile without media extractor),
            // award partial baseline duration score if file size is consistent with duration
            val expectedMinBytes = max(4_000L, call.durationSeconds * 2_000L)
            if (candidate.sizeBytes >= expectedMinBytes) 0.25 else 0.15
        }
        breakdownDetails += "Duration: ${(durationScore * 100).toInt()}pts; "

        // 3. Filename Heuristics (weight: 0.25)
        val nameLower = candidate.displayName.lowercase()
        val digitsOnly = normalizer.extractDigitsOnly(call.normalizedNumber)
        val last10 = normalizer.extractLast10Digits(call.normalizedNumber)
        val last7 = if (last10.length >= 7) last10.takeLast(7) else last10
        val withLeadingZero = if (last10.length == 10) "0$last10" else ""

        var filenameScore = 0.0
        when {
            // Filename contains the normalized number, leading zero version, or last 10 digits
            withLeadingZero.isNotEmpty() && nameLower.contains(withLeadingZero) -> filenameScore = 0.25
            digitsOnly.isNotEmpty() && nameLower.contains(digitsOnly) -> filenameScore = 0.25
            last10.isNotEmpty() && nameLower.contains(last10) -> filenameScore = 0.24
            last7.isNotEmpty() && nameLower.contains(last7) -> filenameScore = 0.18
            // Filename contains dialer recording keywords (e.g. "call", "recording", "rec", "record", "sound", "vivo", "voice", "audio")
            nameLower.contains("call") || nameLower.contains("rec") || nameLower.contains("phone") ||
            nameLower.contains("record") || nameLower.contains("sound") || nameLower.contains("vivo") ||
            nameLower.contains("voice") || nameLower.contains("audio") -> filenameScore = 0.15
            else -> filenameScore = 0.08
        }
        breakdownDetails += "Filename: ${(filenameScore * 100).toInt()}pts"

        val totalScore = timeScore + durationScore + filenameScore
        return Pair(totalScore, breakdownDetails)
    }
}
