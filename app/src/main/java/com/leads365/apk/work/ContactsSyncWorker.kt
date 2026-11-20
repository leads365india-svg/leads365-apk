package com.leads365.apk.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.leads365.apk.contacts.ContactsSyncResult
import com.leads365.apk.contacts.PhoneContactsSyncManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager task for periodic & on-demand synchronization of device phone contacts
 * to the CRM backend to allow contact-aware lead filtering in WhatsApp QR Sync.
 */
class ContactsSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ContactsSyncWorkerEntryPoint {
        fun phoneContactsSyncManager(): PhoneContactsSyncManager
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val force = inputData.getBoolean(KEY_FORCE, false)
        Log.i(TAG, "Starting ContactsSyncWorker. force=$force, attempt=$runAttemptCount")

        val syncManager = try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                ContactsSyncWorkerEntryPoint::class.java
            )
            entryPoint.phoneContactsSyncManager()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot access Hilt EntryPoint: ${e.message}")
            return@withContext Result.failure()
        }

        when (val result = syncManager.syncContacts(force = force)) {
            is ContactsSyncResult.Success -> {
                Log.i(TAG, "ContactsSyncWorker succeeded: synced=${result.syncedCount}, total=${result.totalContacts}")
                Result.success(
                    workDataOf(
                        KEY_SYNCED_COUNT to result.syncedCount,
                        KEY_HASH to result.hash
                    )
                )
            }
            is ContactsSyncResult.Skipped -> {
                Log.i(TAG, "ContactsSyncWorker skipped: ${result.reason}")
                Result.success(
                    workDataOf(
                        KEY_SKIPPED_REASON to result.reason
                    )
                )
            }
            is ContactsSyncResult.Failed -> {
                Log.w(TAG, "ContactsSyncWorker failed: ${result.error}")
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure(
                        workDataOf(KEY_ERROR to result.error)
                    )
                }
            }
        }
    }

    companion object {
        const val TAG = "ContactsSyncWorker"
        const val PERIODIC_WORK_NAME = "periodic_contacts_sync"
        const val ONE_TIME_WORK_NAME = "one_time_contacts_sync"
        const val KEY_FORCE = "force_sync"
        const val KEY_SYNCED_COUNT = "synced_count"
        const val KEY_HASH = "hash"
        const val KEY_SKIPPED_REASON = "skipped_reason"
        const val KEY_ERROR = "error"
    }
}
