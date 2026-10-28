package com.leads365.apk.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.leads365.apk.R
import com.leads365.apk.core.AppResult
import com.leads365.apk.data.repository.CallRepository
import com.leads365.apk.data.repository.PolicyRepository
import com.leads365.apk.data.repository.UploadQueueRepository
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.recordings.RecordingUploadManager
import com.leads365.apk.recordings.UploadExecutionResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class RecordingUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RecordingUploadWorkerEntryPoint {
        fun uploadQueueRepository(): UploadQueueRepository
        fun callRepository(): CallRepository
        fun recordingUploadManager(): RecordingUploadManager
        fun policyRepository(): PolicyRepository
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(KEY_TASK_ID)
        val callId = inputData.getString(KEY_CALL_ID)

        Log.i(TAG, "Executing RecordingUploadWorker for taskId=$taskId, callId=$callId, attempt=$runAttemptCount")

        val entryPoint = try {
            EntryPointAccessors.fromApplication(
                applicationContext,
                RecordingUploadWorkerEntryPoint::class.java
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve worker dependencies via EntryPoint", e)
            return@withContext Result.failure()
        }

        val uploadQueueRepository = entryPoint.uploadQueueRepository()
        val callRepository = entryPoint.callRepository()
        val uploadManager = entryPoint.recordingUploadManager()

        // 1. Resolve Target Upload Task
        val task = when {
            !taskId.isNullOrBlank() -> (uploadQueueRepository.getTaskById(taskId) as? AppResult.Success)?.data
            !callId.isNullOrBlank() -> (uploadQueueRepository.getTaskByCallId(callId) as? AppResult.Success)?.data
            else -> null
        }

        if (task == null) {
            Log.w(TAG, "No upload task found for taskId=$taskId, callId=$callId")
            return@withContext Result.failure()
        }

        // 2. Phase 9: Check if recording sync is paused by agency policy
        val policyRepository = entryPoint.policyRepository()
        if (policyRepository.isRecordingSyncPaused()) {
            Log.i(TAG, "Recording sync is paused by agency policy. Deferring task ${task.id}.")
            uploadQueueRepository.updateStatus(
                task.id,
                UploadTaskEntity.STATE_RETRY_WAIT,
                "Recording sync paused by agency policy"
            )
            return@withContext Result.retry()
        }

        // 3. Check if already uploaded
        if (task.status == UploadTaskEntity.STATE_UPLOADED || task.status == UploadTaskEntity.STATUS_COMPLETED) {
            Log.i(TAG, "Task ${task.id} is already in UPLOADED status. Skipping duplicate upload.")
            return@withContext Result.success()
        }

        // 3. Wi-Fi Only Preference Check
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wifiOnly = prefs.getBoolean(PREF_UPLOAD_WIFI_ONLY, false)
        if (wifiOnly && isMeteredConnection(applicationContext)) {
            Log.i(TAG, "Upload preference is Wi-Fi only, but network is metered. Transitioning to RETRY_WAIT.")
            uploadQueueRepository.updateStatus(
                task.id,
                UploadTaskEntity.STATE_RETRY_WAIT,
                "Waiting for unmetered Wi-Fi connection"
            )
            return@withContext Result.retry()
        }

        // 4. Update status to UPLOADING
        uploadQueueRepository.updateStatus(task.id, UploadTaskEntity.STATE_UPLOADING)
        showNotification(applicationContext, task.fileName, "Uploading call recording...")

        // 5. Execute Streaming Upload
        return@withContext when (val result = uploadManager.uploadRecording(task)) {
            is UploadExecutionResult.Success -> {
                Log.i(TAG, "Upload succeeded for task ${task.id}, call ${task.callId}. AlreadyUploaded=${result.isAlreadyUploaded}")
                uploadQueueRepository.updateStatus(task.id, UploadTaskEntity.STATE_UPLOADED)
                task.callId?.let { cid ->
                    callRepository.updateRecordingMatch(
                        callId = cid,
                        hasRecording = true,
                        recordingUri = task.localFileUri,
                        recordingFileName = task.fileName,
                        recordingFileSizeBytes = task.fileSizeBytes
                    )
                }
                showNotification(applicationContext, task.fileName, "Upload complete", isComplete = true)
                Result.success()
            }
            is UploadExecutionResult.RetryableFailure -> {
                Log.w(TAG, "Upload retryable failure for task ${task.id}: ${result.reason} (attempt=$runAttemptCount)")
                uploadQueueRepository.updateStatus(
                    task.id,
                    UploadTaskEntity.STATE_RETRY_WAIT,
                    result.reason
                )
                uploadQueueRepository.incrementRetry(task.id, result.reason)
                showNotification(applicationContext, task.fileName, "Upload paused: ${result.reason}")
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
            is UploadExecutionResult.RejectedByServer -> {
                Log.e(TAG, "Upload rejected by server for task ${task.id}: HTTP ${result.statusCode} - ${result.reason}")
                val targetState = if (result.reason.contains("DNR", ignoreCase = true)) {
                    UploadTaskEntity.STATE_SKIPPED_DNR
                } else {
                    UploadTaskEntity.STATE_REJECTED_BY_SERVER
                }
                uploadQueueRepository.updateStatus(task.id, targetState, result.reason)
                showNotification(applicationContext, task.fileName, "Upload rejected by server")
                Result.failure()
            }
            is UploadExecutionResult.PermanentFailure -> {
                Log.e(TAG, "Upload permanent failure for task ${task.id}: ${result.reason}")
                uploadQueueRepository.updateStatus(
                    task.id,
                    UploadTaskEntity.STATE_FAILED_PERMANENT,
                    result.reason
                )
                showNotification(applicationContext, task.fileName, "Upload failed permanently")
                Result.failure()
            }
        }
    }

    private fun isMeteredConnection(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return true
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun showNotification(context: Context, fileName: String, message: String, isComplete: Boolean = false) {
        try {
            ensureNotificationChannel(context)
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(if (isComplete) "Recording Uploaded" else "Recording Upload")
                .setContentText("$fileName: $message")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(!isComplete)
                .setAutoCancel(isComplete)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notification permission might be denied on Android 13+
        } catch (e: Exception) {
            // Non-fatal notification error
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Call Recording Uploads"
            val description = "Progress and status updates for call recording uploads"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                this.description = description
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val TAG = "RecordingUploadWorker"
        const val KEY_TASK_ID = "key_task_id"
        const val KEY_CALL_ID = "key_call_id"
        const val KEY_FILE_URI = "key_file_uri"
        const val WORK_NAME_PREFIX = "upload_recording_work_"
        const val PREFS_NAME = "com.leads365.apk.preferences"
        const val PREF_UPLOAD_WIFI_ONLY = "pref_upload_wifi_only"
        const val NOTIFICATION_CHANNEL_ID = "channel_recording_uploads"
        const val NOTIFICATION_ID = 2001

        fun enqueueUniqueUpload(
            context: Context,
            taskId: String,
            callId: String?,
            wifiOnly: Boolean? = null
        ) {
            val resolvedCallId = if (!callId.isNullOrBlank()) callId else taskId
            val isWifiOnly = wifiOnly ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_UPLOAD_WIFI_ONLY, false)
            val networkType = if (isWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()

            val request = OneTimeWorkRequestBuilder<RecordingUploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_TASK_ID to taskId,
                        KEY_CALL_ID to resolvedCallId
                    )
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_PREFIX + resolvedCallId,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
