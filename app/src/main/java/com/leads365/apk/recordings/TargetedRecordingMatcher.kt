package com.leads365.apk.recordings

import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.CandidateScore
import com.leads365.apk.core.MatchStatus
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.RecordingMatchResult
import com.leads365.apk.core.TimeHelpers
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

        val fileTimeMs = candidate.lastModifiedEpochMs
        val callStartMs = call.startTimeEpochMs
        val callEndMs = call.endTimeEpochMs

        // Extract timestamp from filename if present (Vivo, Samsung, Xiaomi, Oppo dialers)
        val filenameTimeMs = extractTimestampEpochMs(candidate.displayName)
        val filenameDiffMs: Long? = if (filenameTimeMs != null) {
            val dStart = abs(filenameTimeMs - callStartMs)
            val dEnd = abs(filenameTimeMs - callEndMs)
            min(dStart, dEnd)
        } else null

        // 1. Time Score (weight: 0.40)
        val isFileTimeIdeal = fileTimeMs in (callStartMs - 120_000L)..(callEndMs + 120_000L)
        val isFilenameTimeIdeal = filenameDiffMs != null && filenameDiffMs <= 120_000L

        val timeScore = when {
            isFileTimeIdeal || isFilenameTimeIdeal -> 0.40
            fileTimeMs in (callEndMs + 120_001L)..(callEndMs + 300_000L) -> 0.20
            filenameDiffMs != null && filenameDiffMs <= 300_000L -> 0.20
            fileTimeMs in (callEndMs + 300_001L)..(callEndMs + 600_000L) -> 0.12
            fileTimeMs in (callStartMs - 240_000L)..(callStartMs - 120_001L) -> 0.15
            fileTimeMs == 0L || fileTimeMs == callEndMs -> 0.35
            else -> 0.05
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
            // 1,000 bytes/sec safely covers low bitrate formats (AMR-NB ~1.5KB/s, AAC-HE ~2KB/s)
            val expectedMinBytes = max(2_000L, call.durationSeconds * 1_000L)
            if (candidate.sizeBytes >= expectedMinBytes) 0.25 else if (candidate.sizeBytes > 1_000L) 0.18 else 0.10
        }
        breakdownDetails += "Duration: ${(durationScore * 100).toInt()}pts; "

        // 3. Filename Heuristics (weight: 0.25)
        val nameLower = candidate.displayName.lowercase()
        val digitsOnly = normalizer.extractDigitsOnly(call.normalizedNumber)
        val last10 = normalizer.extractLast10Digits(call.normalizedNumber)
        val last7 = if (last10.length >= 7) last10.takeLast(7) else last10
        val withLeadingZero = if (last10.length == 10) "0$last10" else ""

        var filenameScore = 0.0
        val fDiff = filenameDiffMs
        when {
            // Filename contains the normalized number, leading zero version, or last 10 digits
            withLeadingZero.isNotEmpty() && nameLower.contains(withLeadingZero) -> filenameScore = 0.25
            digitsOnly.isNotEmpty() && nameLower.contains(digitsOnly) -> filenameScore = 0.25
            last10.isNotEmpty() && nameLower.contains(last10) -> filenameScore = 0.24
            last7.isNotEmpty() && nameLower.contains(last7) -> filenameScore = 0.18
            fDiff != null && fDiff <= 60_000L -> filenameScore = 0.22
            fDiff != null && fDiff <= 120_000L -> filenameScore = 0.18
            fDiff != null && fDiff <= 180_000L -> filenameScore = 0.12
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

    private fun extractTimestampEpochMs(filename: String): Long? {
        val formats = listOf(
            "yyyyMMdd_HHmmss",
            "yyyyMMdd-HHmmss",
            "yyyy-MM-dd_HH-mm-ss",
            "yyyy-MM-dd-HH-mm-ss",
            "yyyyMMddHHmmss",
            "yyMMdd_HHmmss",
            "yyMMddHHmmss"
        )
        val regex = Regex("""(20\d{6}[_-]?\d{6}|\d{6}[_-]?\d{6}|20\d{2}-\d{2}-\d{2}[_-]\d{2}-\d{2}-\d{2})""")
        val match = regex.find(filename) ?: return null
        val dateStr = match.value

        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                    isLenient = false
                }
                val date = sdf.parse(dateStr)
                if (date != null && date.time > 1500000000000L) {
                    return date.time
                }
            } catch (ignored: Exception) {}
        }
        return null
    }
}
