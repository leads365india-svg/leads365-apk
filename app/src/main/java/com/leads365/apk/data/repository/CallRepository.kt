package com.leads365.apk.data.repository

import com.leads365.apk.core.AppResult
import com.leads365.apk.database.entity.CallEntity
import kotlinx.coroutines.flow.Flow

interface CallRepository {
    fun getAllCallsFlow(): Flow<List<CallEntity>>
    fun getLeadCallsFlow(): Flow<List<CallEntity>>
    fun getCallsForNumberFlow(normalizedNumber: String): Flow<List<CallEntity>>
    suspend fun getRecentCalls(limit: Int): AppResult<List<CallEntity>>
    suspend fun getCallById(id: String): AppResult<CallEntity?>
    suspend fun getByClientCallId(clientCallId: String): AppResult<CallEntity?>
    suspend fun findCallBySignature(normalizedNumber: String, startTimeEpochMs: Long, toleranceMs: Long = 5000L): AppResult<CallEntity?>
    suspend fun recordCall(call: CallEntity): AppResult<Unit>
    suspend fun updateCall(call: CallEntity): AppResult<Unit>
    suspend fun updateOutcome(callId: String, outcome: String, notes: String?, nextFollowUpEpochMs: Long?): AppResult<Unit>
    suspend fun updateRecordingMatch(callId: String, hasRecording: Boolean, recordingUri: String?, recordingFileName: String?, recordingFileSizeBytes: Long?): AppResult<Unit>
    suspend fun getPendingSyncCalls(): AppResult<List<CallEntity>>
    suspend fun reportCallToBackend(callId: String): AppResult<com.leads365.apk.network.models.CallReportResponse>
    suspend fun syncCallsFromBackend(): AppResult<Int> = AppResult.Success(0)
    suspend fun deleteCallById(id: String): AppResult<Unit>
    suspend fun clearAll(): AppResult<Unit>
}
