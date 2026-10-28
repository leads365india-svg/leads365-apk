package com.leads365.apk.data.repository

import android.provider.CallLog
import com.leads365.apk.core.AppError
import com.leads365.apk.core.AppResult
import com.leads365.apk.database.dao.CallDao
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.network.api.CallApiService
import com.leads365.apk.network.models.CallReportRequest
import com.leads365.apk.network.models.CallReportResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallRepositoryImpl @Inject constructor(
    private val callDao: CallDao,
    private val callApiService: CallApiService
) : CallRepository {

    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    constructor(
        callDao: CallDao,
        callApiService: CallApiService,
        dispatcher: CoroutineDispatcher
    ) : this(callDao, callApiService) {
        this.ioDispatcher = dispatcher
    }

    override fun getAllCallsFlow(): Flow<List<CallEntity>> = callDao.getAllCallsFlow()

    override fun getLeadCallsFlow(): Flow<List<CallEntity>> = callDao.getLeadCallsFlow()

    override fun getCallsForNumberFlow(normalizedNumber: String): Flow<List<CallEntity>> =
        callDao.getCallsForNumberFlow(normalizedNumber)

    override suspend fun getRecentCalls(limit: Int): AppResult<List<CallEntity>> = withContext(ioDispatcher) {
        try {
            AppResult.Success(callDao.getRecentCalls(limit))
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to fetch recent calls: ${e.message}", e))
        }
    }

    override suspend fun getCallById(id: String): AppResult<CallEntity?> = withContext(ioDispatcher) {
        try {
            AppResult.Success(callDao.getById(id))
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to fetch call $id: ${e.message}", e))
        }
    }

    override suspend fun getByClientCallId(clientCallId: String): AppResult<CallEntity?> = withContext(ioDispatcher) {
        try {
            AppResult.Success(callDao.getByClientCallId(clientCallId))
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to fetch call by clientCallId $clientCallId: ${e.message}", e))
        }
    }

    override suspend fun findCallBySignature(
        normalizedNumber: String,
        startTimeEpochMs: Long,
        toleranceMs: Long
    ): AppResult<CallEntity?> = withContext(ioDispatcher) {
        try {
            val minStart = startTimeEpochMs - toleranceMs
            val maxStart = startTimeEpochMs + toleranceMs
            AppResult.Success(callDao.findCallBySignature(normalizedNumber, minStart, maxStart))
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to search call by signature: ${e.message}", e))
        }
    }

    override suspend fun recordCall(call: CallEntity): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            callDao.insert(call)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to record call: ${e.message}", e))
        }
    }

    override suspend fun updateCall(call: CallEntity): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            callDao.update(call)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to update call: ${e.message}", e))
        }
    }

    override suspend fun updateOutcome(
        callId: String,
        outcome: String,
        notes: String?,
        nextFollowUpEpochMs: Long?
    ): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            callDao.updateOutcome(callId, outcome, notes, nextFollowUpEpochMs, "PENDING")
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to update call outcome: ${e.message}", e))
        }
    }

    override suspend fun updateRecordingMatch(
        callId: String,
        hasRecording: Boolean,
        recordingUri: String?,
        recordingFileName: String?,
        recordingFileSizeBytes: Long?
    ): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            callDao.updateRecording(
                callId = callId,
                hasRecording = hasRecording,
                recordingUri = recordingUri,
                recordingFileName = recordingFileName,
                recordingFileSizeBytes = recordingFileSizeBytes
            )
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to update call recording match: ${e.message}", e))
        }
    }

    override suspend fun getPendingSyncCalls(): AppResult<List<CallEntity>> = withContext(ioDispatcher) {
        try {
            AppResult.Success(callDao.getPendingSyncCalls())
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to fetch pending calls: ${e.message}", e))
        }
    }

    override suspend fun reportCallToBackend(callId: String): AppResult<CallReportResponse> = withContext(ioDispatcher) {
        val call = try {
            callDao.getById(callId)
        } catch (e: Exception) {
            return@withContext AppResult.Failure(AppError.StorageError("Failed to find call $callId", e))
        }

        if (call == null) {
            return@withContext AppResult.Failure(AppError.StorageError("Call $callId not found in database"))
        }

        val directionStr = when (call.callType) {
            CallLog.Calls.INCOMING_TYPE -> "INCOMING"
            CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
            CallLog.Calls.MISSED_TYPE -> "MISSED"
            CallLog.Calls.REJECTED_TYPE -> "REJECTED"
            else -> "OUTGOING"
        }

        val request = CallReportRequest(
            clientCallId = call.clientCallId,
            leadId = call.leadId ?: "",
            phoneNumber = call.normalizedNumber,
            callDirection = directionStr,
            startTimeMs = call.startTimeEpochMs,
            durationSeconds = call.durationSeconds,
            callOutcome = call.callOutcome,
            notes = call.notes,
            nextFollowUpAt = call.nextFollowUpEpochMs,
            simId = call.simId
        )

        try {
            val response = callApiService.reportCall(request)
            if (response.success) {
                callDao.updateSyncStatus(callId, "SYNCED", null)
                AppResult.Success(response)
            } else {
                val errorMsg = response.error ?: response.message ?: "Failed to report call"
                callDao.updateSyncStatus(callId, "FAILED", errorMsg)
                AppResult.Failure(AppError.NetworkError(errorMsg))
            }
        } catch (e: HttpException) {
            val code = e.code()
            val errorMsg = "Server error $code: ${e.message()}"
            callDao.updateSyncStatus(callId, "FAILED", errorMsg)
            AppResult.Failure(AppError.NetworkError(errorMsg, httpCode = code, cause = e))
        } catch (e: IOException) {
            val errorMsg = "Network unavailable: offline call preserved"
            callDao.updateSyncStatus(callId, "PENDING", errorMsg)
            AppResult.Failure(AppError.NetworkError(errorMsg, cause = e))
        } catch (e: Exception) {
            val errorMsg = "Unexpected error reporting call: ${e.message}"
            callDao.updateSyncStatus(callId, "FAILED", errorMsg)
            AppResult.Failure(AppError.UnknownError(errorMsg, cause = e))
        }
    }

    override suspend fun deleteCallById(id: String): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            callDao.deleteById(id)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to delete call $id: ${e.message}", e))
        }
    }

    override suspend fun clearAll(): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            callDao.clearAll()
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.StorageError("Failed to clear calls: ${e.message}", e))
        }
    }
}
