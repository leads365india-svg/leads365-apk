package com.leads365.apk.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.leads365.apk.calls.CallLogReader
import com.leads365.apk.calls.CallProcessingManager
import com.leads365.apk.calls.CallProcessingResult
import com.leads365.apk.core.AppError
import com.leads365.apk.core.AppResult
import com.leads365.apk.data.repository.CallRepository
import com.leads365.apk.data.repository.LeadRepository
import com.leads365.apk.data.repository.PolicyRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LeadSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface LeadSyncWorkerEntryPoint {
        fun leadRepository(): LeadRepository
        fun policyRepository(): PolicyRepository
        fun callRepository(): CallRepository
        fun callProcessingManager(): CallProcessingManager
        fun workManagerScheduler(): WorkManagerScheduler
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val forceFullSync = inputData.getBoolean(KEY_FORCE_FULL_SYNC, false)
        Log.i(TAG, "Starting LeadSyncWorker. forceFullSync=$forceFullSync, attempt=$runAttemptCount")

        val repository = try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                LeadSyncWorkerEntryPoint::class.java
            )
            // Phase 9: Piggyback policy sync on each lead sync cycle
            try {
                val policyRepo = entryPoint.policyRepository()
                policyRepo.syncPolicy()
                Log.d(TAG, "Policy synced during lead sync")
            } catch (e: Exception) {
                Log.w(TAG, "Policy sync failed (non-blocking): ${e.message}")
            }

            // 1. Safety-Net: Sync pending calls to backend (including recovering DEFERRED_IDENTITY calls)
            try {
                val callRepo = entryPoint.callRepository()
                val syncedCount = callRepo.syncPendingCallsToBackend()
                Log.d(TAG, "Synced pending call(s) during background lead sync: $syncedCount")
            } catch (e: Exception) {
                Log.w(TAG, "Pending call sync failed (non-blocking): ${e.message}")
            }

            // 2. Safety-Net: Find and process recent calls from the last 1 hour
            // that OEM battery savers may have blocked CallStateReceiver from catching
            try {
                val callLogReader = CallLogReader(applicationContext)
                val recentCalls = callLogReader.findRecentCalls(lookbackMs = 3_600_000L)
                if (recentCalls.isNotEmpty()) {
                    Log.i(TAG, "LeadSyncWorker found ${recentCalls.size} call(s) in last 1h safety-net scan")
                    val processingManager = entryPoint.callProcessingManager()
                    val callRepo = entryPoint.callRepository()
                    for (call in recentCalls) {
                        try {
                            val processResult = processingManager.processCallEvent(call)
                            val clientCallId = when (processResult) {
                                is CallProcessingResult.Recorded -> processResult.call.clientCallId
                                is CallProcessingResult.UnlinkedIncomingRecorded -> processResult.call.clientCallId
                                else -> null
                            }
                            if (!clientCallId.isNullOrBlank()) {
                                callRepo.reportCallToBackend(clientCallId)
                            }
                        } catch (callErr: Exception) {
                            Log.w(TAG, "Failed to process recovered call ${call.id}: ${callErr.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Safety-net call recovery scan failed (non-blocking): ${e.message}")
            }

            // 3. Safety-Net: Flush any PENDING / RETRY_WAIT recording uploads
            try {
                val scheduler = entryPoint.workManagerScheduler()
                val resumedCount = scheduler.resumePendingUploads()
                Log.d(TAG, "Resumed $resumedCount pending upload task(s) during background lead sync")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resume pending uploads in LeadSyncWorker: ${e.message}")
            }

            entryPoint.leadRepository()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve LeadRepository from EntryPoint", e)
            return@withContext Result.failure()
        }

        when (val result = repository.syncLeads(forceFullSync)) {
            is AppResult.Success -> {
                val data = result.data
                Log.i(TAG, "LeadSyncWorker completed successfully: updated=${data.insertedOrUpdatedCount}, deleted=${data.deletedCount}, isFull=${data.isFullSync}")
                Result.success(
                    workDataOf(
                        KEY_UPDATED_COUNT to data.insertedOrUpdatedCount,
                        KEY_DELETED_COUNT to data.deletedCount,
                        KEY_IS_FULL_SYNC to data.isFullSync
                    )
                )
            }
            is AppResult.Failure -> {
                when (val error = result.error) {
                    is AppError.RateLimitError -> {
                        Log.w(TAG, "Rate limit hit during lead sync. Will retry with backoff. Message: ${error.message}")
                        Result.retry()
                    }
                    is AppError.AuthError -> {
                        Log.w(TAG, "Auth error during lead sync. Session invalid or expired: ${error.message}")
                        Result.failure()
                    }
                    is AppError.NetworkError -> {
                        Log.w(TAG, "Network error during lead sync: ${error.message}")
                        if (runAttemptCount < 3) {
                            Result.retry()
                        } else {
                            Result.failure()
                        }
                    }
                    else -> {
                        Log.e(TAG, "Lead sync error: ${error.message}")
                        if (runAttemptCount < 3) {
                            Result.retry()
                        } else {
                            Result.failure()
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "LeadSyncWorker"
        const val PERIODIC_WORK_NAME = "periodic_lead_sync_work"
        const val ONE_TIME_WORK_NAME = "one_time_lead_sync_work"
        const val KEY_FORCE_FULL_SYNC = "key_force_full_sync"
        const val KEY_UPDATED_COUNT = "key_updated_count"
        const val KEY_DELETED_COUNT = "key_deleted_count"
        const val KEY_IS_FULL_SYNC = "key_is_full_sync"
    }
}
