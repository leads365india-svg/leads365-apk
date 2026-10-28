package com.leads365.apk.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.leads365.apk.data.repository.AuthRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Phase 12: BootCompletedReceiver.
 * Reschedules periodic lead synchronization and recovers pending uploads upon device reboot
 * or app package replacement without blocking the main broadcast thread.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootCompletedEntryPoint {
        fun workManagerScheduler(): WorkManagerScheduler
        fun authRepository(): AuthRepository
    }

    var workManagerScheduler: WorkManagerScheduler? = null
    var authRepository: AuthRepository? = null

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "BootCompletedReceiver received action: $action")

        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )

        if (action !in validActions) {
            Log.d(TAG, "Ignoring non-boot action: $action")
            return
        }

        val auth = authRepository ?: try {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                BootCompletedEntryPoint::class.java
            ).authRepository()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve AuthRepository", e)
            null
        }

        if (auth == null || !auth.isLoggedIn()) {
            Log.i(TAG, "User not authenticated; skipping background task rescheduling on reboot")
            return
        }

        val scheduler = workManagerScheduler ?: try {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                BootCompletedEntryPoint::class.java
            ).workManagerScheduler()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve WorkManagerScheduler", e)
            null
        } ?: return

        Log.i(TAG, "Reboot detected for authenticated user. Rescheduling periodic lead sync, heartbeat, and pending uploads.")
        scheduler.schedulePeriodicLeadSync()
        scheduler.schedulePeriodicHeartbeat()

        val pendingResult = try {
            goAsync()
        } catch (e: Exception) {
            null
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                scheduler.resumePendingUploads()
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming uploads after boot: ${e.message}", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val TAG = "BootCompletedReceiver"
    }
}
