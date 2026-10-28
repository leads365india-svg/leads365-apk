package com.leads365.apk.recordings

import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.RecordingDiscoveryMethod

interface RecordingSource {
    val discoveryMethod: RecordingDiscoveryMethod
    fun isConfigured(): Boolean
    fun isAccessible(): Boolean
    fun queryCandidates(
        callStartEpochMs: Long,
        callEndEpochMs: Long,
        callDurationSec: Long,
        normalizedNumber: String
    ): List<RecordingCandidate>
}
