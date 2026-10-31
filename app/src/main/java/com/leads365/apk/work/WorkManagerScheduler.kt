package com.leads365.apk.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.leads365.apk.core.AppResult
import com.leads365.apk.data.repository.PolicyRepository
import com.leads365.apk.data.repository.UploadQueueRepository
import com.leads365.apk.database.entity.UploadTaskEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class WorkManagerScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager,
    private val uploadQueueRepository: UploadQueueRepository? = null,
    private val policyRepository: PolicyRepository? = null
) {

    open fun scheduleUploadTask(
        taskId: String,
        fileUri: String,
        callId: String? = null
    ) {
        scheduleUploadTask(taskId, fileUri, callId, ExistingWorkPolicy.KEEP)
    }

    open fun scheduleUploadTask(
        taskId: String,
        fileUri: String,
        callId: String?,
        policy: ExistingWorkPolicy
    ) {
        val prefs = context.getSharedPreferences(RecordingUploadWorker.PREFS_NAME, Context.MODE_PRIVATE)
        val wifiOnly = prefs.getBoolean(RecordingUploadWorker.PREF_UPLOAD_WIFI_ONLY, false)
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val resolvedCallId = if (!callId.isNullOrBlank()) callId else taskId

        val uploadRequest = OneTimeWorkRequestBuilder<RecordingUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                15,
                TimeUnit.SECONDS
            )
            .setInputData(
                workDataOf(
                    RecordingUploadWorker.KEY_TASK_ID to taskId,
                    RecordingUploadWorker.KEY_CALL_ID to resolvedCallId,
                    RecordingUploadWorker.KEY_FILE_URI to fileUri
                )
            )
            .addTag("recording_upload")
            .addTag("task_$taskId")
            .build()

        // Enqueue with configured policy (KEEP for normal scheduling, REPLACE when constraints/settings change)
        workManager.enqueueUniqueWork(
            "${RecordingUploadWorker.WORK_NAME_PREFIX}$resolvedCallId",
            policy,
            uploadRequest
        )
    }

    open suspend fun resumePendingUploads(policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP): Int {
        val repo = uploadQueueRepository
        if (repo == null) {
            Log.d(TAG, "UploadQueueRepository not provided in scheduler; skipping upload resumption")
            return 0
        }

        val policyRepo = policyRepository
        if (policyRepo != null && policyRepo.isRecordingSyncPaused()) {
            Log.i(TAG, "Recording sync is paused by agency policy; skipping upload resumption")
            return 0
        }

        // 0. Clean expired terminal tasks older than 7 days to safely bound queue growth
        try {
            repo.cleanExpiredTerminalTasks()
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning expired terminal tasks: ${e.message}")
        }

        var resumedCount = 0

        // 1. Recover any tasks stuck in UPLOADING due to process termination / reboot
        val stuckTasks = (repo.getTasksByStatus(UploadTaskEntity.STATE_UPLOADING) as? AppResult.Success)?.data.orEmpty()
        for (task in stuckTasks) {
            Log.i(TAG, "Resetting interrupted upload task ${task.id} to RETRY_WAIT after process termination")
            repo.updateStatus(task.id, UploadTaskEntity.STATE_RETRY_WAIT, "Resumed after process death / reboot")
        }

        // 2. Resume all pending and retryable tasks
        val pending = (repo.getPendingTasks() as? AppResult.Success)?.data.orEmpty()
        val retryWait = (repo.getTasksByStatus(UploadTaskEntity.STATE_RETRY_WAIT) as? AppResult.Success)?.data.orEmpty()
        val allToResume = (pending + retryWait).distinctBy { it.id }

        for (task in allToResume) {
            if (policy == ExistingWorkPolicy.KEEP) {
                scheduleUploadTask(task.id, task.localFileUri, task.callId)
            } else {
                scheduleUploadTask(task.id, task.localFileUri, task.callId, policy = policy)
            }
            resumedCount++
        }

        Log.i(TAG, "Resumed $resumedCount pending/retryable upload tasks safely into WorkManager (policy=$policy)")
        return resumedCount
    }

    open suspend fun rescheduleAllPendingUploads(): Int {
        return resumePendingUploads(policy = ExistingWorkPolicy.REPLACE)
    }

    fun getWorkInfosByTagFlow(tag: String): Flow<List<WorkInfo>> {
        return workManager.getWorkInfosByTagFlow(tag)
    }

    fun cancelUploadTask(taskId: String) {
        workManager.cancelUniqueWork("${RecordingUploadWorker.WORK_NAME_PREFIX}$taskId")
    }

    open fun schedulePeriodicLeadSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicSyncRequest = androidx.work.PeriodicWorkRequestBuilder<LeadSyncWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                androidx.work.WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(LeadSyncWorker.TAG)
            .build()

        // Phase 12: ExistingPeriodicWorkPolicy.KEEP prevents duplicate periodic jobs and retains schedule
        workManager.enqueueUniquePeriodicWork(
            LeadSyncWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicSyncRequest
        )
    }

    open fun triggerOneTimeLeadSync(forceFullSync: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeRequest = OneTimeWorkRequestBuilder<LeadSyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(LeadSyncWorker.KEY_FORCE_FULL_SYNC to forceFullSync))
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                androidx.work.WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(LeadSyncWorker.TAG)
            .build()

        // Phase 12: If forceFullSync, replace to run immediately; otherwise KEEP to avoid redundant bursts
        val policy = if (forceFullSync) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager.enqueueUniqueWork(
            LeadSyncWorker.ONE_TIME_WORK_NAME,
            policy,
            oneTimeRequest
        )
    }

    open fun schedulePeriodicHeartbeat() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicHeartbeatRequest = androidx.work.PeriodicWorkRequestBuilder<DeviceHeartbeatWorker>(
            24, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                androidx.work.WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(DeviceHeartbeatWorker.TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            DeviceHeartbeatWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicHeartbeatRequest
        )
    }

    open fun triggerOneTimeHeartbeat() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeRequest = OneTimeWorkRequestBuilder<DeviceHeartbeatWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                androidx.work.WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(DeviceHeartbeatWorker.TAG)
            .build()

        workManager.enqueueUniqueWork(
            DeviceHeartbeatWorker.ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            oneTimeRequest
        )
    }

    companion object {
        private const val TAG = "WorkManagerScheduler"
    }
}
