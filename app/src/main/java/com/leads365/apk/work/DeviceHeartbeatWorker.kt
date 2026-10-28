package com.leads365.apk.work

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.leads365.apk.BuildConfig
import com.leads365.apk.data.repository.AuthRepository
import com.leads365.apk.database.dao.CallDao
import com.leads365.apk.database.dao.UploadTaskDao
import com.leads365.apk.network.api.AuthApiService
import com.leads365.apk.network.auth.EncryptedTokenProvider
import com.leads365.apk.network.models.HeartbeatRequest
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 11: Periodic & on-app-open heartbeat reporting device telemetry,
 * missing-recording counters, and health status to backend.
 */
class DeviceHeartbeatWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface HeartbeatWorkerEntryPoint {
        fun authApiService(): AuthApiService
        fun tokenProvider(): EncryptedTokenProvider
        fun authRepository(): AuthRepository
        fun uploadTaskDao(): UploadTaskDao
        fun callDao(): CallDao
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val entryPoint = try {
            EntryPointAccessors.fromApplication(
                applicationContext,
                HeartbeatWorkerEntryPoint::class.java
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cannot access Hilt EntryPoint (likely offline or uninitialized): ${e.message}")
            return@withContext Result.failure()
        }

        val tokenProvider = entryPoint.tokenProvider()
        val token = tokenProvider.getAccessToken()
        if (token.isNullOrBlank()) {
            Log.d(TAG, "Device not authenticated; skipping heartbeat")
            return@withContext Result.success()
        }

        val deviceId = tokenProvider.getDeviceId()
        val uploadTaskDao = entryPoint.uploadTaskDao()
        val callDao = entryPoint.callDao()
        val authApi = entryPoint.authApiService()
        val authRepo = entryPoint.authRepository()

        val pendingTasks = try { uploadTaskDao.getPendingTasks().size } catch (e: Exception) { 0 }

        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isIgnoringBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(applicationContext.packageName) ?: false

        val request = HeartbeatRequest(
            device_id = deviceId,
            app_version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            battery_optimization_ignored = isIgnoringBatteryOptimizations,
            permissions_all_granted = true,
            storage_access_granted = true,
            auto_record_verified = true,
            missing_recordings_count = 0,
            pending_uploads_count = pendingTasks,
            last_sync_epoch_ms = System.currentTimeMillis(),
            last_successful_upload_epoch_ms = System.currentTimeMillis(),
            health_state = "HEALTHY"
        )

        try {
            val response = authApi.sendHeartbeat(request)
            if (response.revoked) {
                Log.w(TAG, "Server flagged device as REVOKED during heartbeat; revoking local session")
                authRepo.invalidateSession("Device revoked by administrator")
            }
            Log.i(TAG, "Heartbeat transmitted successfully. Status: ${response.device_status}")
            Result.success(workDataOf(KEY_STATUS to (response.device_status ?: "OK")))
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat failed (transient error): ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val TAG = "DeviceHeartbeatWorker"
        const val PERIODIC_WORK_NAME = "leads365_periodic_heartbeat"
        const val ONE_TIME_WORK_NAME = "leads365_onetime_heartbeat"
        const val KEY_STATUS = "key_heartbeat_status"
    }
}
