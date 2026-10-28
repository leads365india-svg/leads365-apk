package com.leads365.apk.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.leads365.apk.database.dao.FollowUpDao
import com.leads365.apk.database.entity.FollowUpEntity
import com.leads365.apk.notifications.LeadsNotificationManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FollowUpReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface FollowUpReminderWorkerEntryPoint {
        fun followUpDao(): FollowUpDao
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val followUpId = inputData.getString(KEY_FOLLOW_UP_ID) ?: return@withContext Result.failure()
        Log.i(TAG, "Triggering follow-up reminder notification for id=$followUpId")

        val dao = try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                FollowUpReminderWorkerEntryPoint::class.java
            )
            entryPoint.followUpDao()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve FollowUpDao from EntryPoint", e)
            null
        }

        if (dao != null) {
            val followUp = dao.getById(followUpId)
            if (followUp != null) {
                // Only trigger reminder if still pending
                if (followUp.status == FollowUpEntity.STATUS_PENDING) {
                    LeadsNotificationManager.showFollowUpReminderNotification(
                        context = applicationContext,
                        followUpId = followUp.id,
                        leadId = followUp.leadId,
                        leadName = followUp.leadName,
                        leadPhone = followUp.leadPhone,
                        title = followUp.title,
                        notes = followUp.notes
                    )
                } else {
                    Log.d(TAG, "Follow-up $followUpId is status=${followUp.status}; skipping reminder notification")
                }
            } else {
                Log.d(TAG, "Follow-up $followUpId was deleted; skipping reminder notification")
            }
        } else {
            // Fallback from worker inputData only if dao was completely unavailable
            val leadId = inputData.getString(KEY_LEAD_ID)
            val leadName = inputData.getString(KEY_LEAD_NAME) ?: ""
            val leadPhone = inputData.getString(KEY_LEAD_PHONE) ?: ""
            val title = inputData.getString(KEY_TITLE) ?: "Follow-up"
            val notes = inputData.getString(KEY_NOTES)

            LeadsNotificationManager.showFollowUpReminderNotification(
                context = applicationContext,
                followUpId = followUpId,
                leadId = leadId,
                leadName = leadName,
                leadPhone = leadPhone,
                title = title,
                notes = notes
            )
        }

        Result.success()
    }

    companion object {
        private const val TAG = "FollowUpReminderWorker"
        const val KEY_FOLLOW_UP_ID = "follow_up_id"
        const val KEY_LEAD_ID = "lead_id"
        const val KEY_LEAD_NAME = "lead_name"
        const val KEY_LEAD_PHONE = "lead_phone"
        const val KEY_TITLE = "title"
        const val KEY_NOTES = "notes"
        const val WORK_TAG_PREFIX = "followup_reminder_"
    }
}
