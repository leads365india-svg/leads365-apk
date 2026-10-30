package com.leads365.apk.data.repository

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.leads365.apk.core.AppError
import com.leads365.apk.core.AppResult
import com.leads365.apk.database.dao.FollowUpDao
import com.leads365.apk.database.dao.LeadDao
import com.leads365.apk.database.entity.FollowUpEntity
import com.leads365.apk.network.api.FollowUpApiService
import com.leads365.apk.network.models.CompleteFollowUpRequest
import com.leads365.apk.network.models.CreateFollowUpRequest
import com.leads365.apk.network.models.UpdateFollowUpRequest
import com.leads365.apk.notifications.LeadsNotificationManager
import com.leads365.apk.work.FollowUpReminderWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FollowUpRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val followUpDao: FollowUpDao,
    private val followUpApiService: FollowUpApiService,
    private val leadDao: LeadDao,
    private val workManager: WorkManager
) : FollowUpRepository {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun getAllFollowUpsFlow(): Flow<List<FollowUpEntity>> {
        return followUpDao.getAllFollowUpsFlow()
    }

    override fun getPendingFollowUpsFlow(): Flow<List<FollowUpEntity>> {
        return followUpDao.getFollowUpsByStatusFlow(FollowUpEntity.STATUS_PENDING)
    }

    override suspend fun getTodayFollowUps(): List<FollowUpEntity> {
        val now = System.currentTimeMillis()
        val (_, endOfToday) = getDayBounds()
        val allPending = followUpDao.getFollowUpsByStatusFlow(FollowUpEntity.STATUS_PENDING).first()

        return allPending.filter {
            it.scheduledAtEpochMs in now..endOfToday
        }.sortedBy { it.scheduledAtEpochMs }
    }

    override suspend fun getUpcomingFollowUps(): List<FollowUpEntity> {
        val (_, endOfToday) = getDayBounds()
        val allPending = followUpDao.getFollowUpsByStatusFlow(FollowUpEntity.STATUS_PENDING).first()

        return allPending.filter {
            it.scheduledAtEpochMs > endOfToday
        }.sortedBy { it.scheduledAtEpochMs }
    }

    override suspend fun getOverdueFollowUps(): List<FollowUpEntity> {
        val now = System.currentTimeMillis()
        val allPending = followUpDao.getFollowUpsByStatusFlow(FollowUpEntity.STATUS_PENDING).first()

        return allPending.filter {
            it.scheduledAtEpochMs < now
        }.sortedBy { it.scheduledAtEpochMs }
    }

    override suspend fun createFollowUp(
        leadId: String?,
        leadName: String,
        leadPhone: String,
        title: String,
        scheduledAtEpochMs: Long,
        priority: String,
        notes: String,
        sourceCallId: String?
    ): AppResult<FollowUpEntity> {
        val resolvedTitle = if (title.isNotBlank()) title else "Call $leadName"

        // Deduplication & idempotency check
        val existingMatch = if (!sourceCallId.isNullOrBlank()) {
            followUpDao.getBySourceCallId(sourceCallId)
        } else if (!leadId.isNullOrBlank()) {
            followUpDao.findPendingByLeadAndTime(leadId, scheduledAtEpochMs - 60000L, scheduledAtEpochMs + 60000L)
        } else null

        if (existingMatch != null) {
            val updated = existingMatch.copy(
                title = resolvedTitle,
                notes = if (notes.isNotBlank()) notes else existingMatch.notes,
                priority = priority,
                scheduledAtEpochMs = scheduledAtEpochMs,
                sourceCallId = sourceCallId ?: existingMatch.sourceCallId,
                syncStatus = FollowUpEntity.SYNC_STATUS_PENDING_UPDATE,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            followUpDao.update(updated)
            scheduleReminder(updated)
            try {
                val isoDate = isoFormat.format(Date(scheduledAtEpochMs))
                followUpApiService.updateFollowUp(
                    id = updated.id,
                    request = UpdateFollowUpRequest(scheduledAt = isoDate, notes = updated.notes, priority = updated.priority)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Offline update queued for deduplicated follow-up ${updated.id}")
            }
            return AppResult.Success(updated)
        }

        val localId = "fu_${UUID.randomUUID().toString().take(12)}"
        val entity = FollowUpEntity(
            id = localId,
            leadId = leadId,
            leadName = leadName,
            leadPhone = leadPhone,
            title = resolvedTitle,
            type = "call",
            scheduledAtEpochMs = scheduledAtEpochMs,
            status = FollowUpEntity.STATUS_PENDING,
            priority = priority,
            notes = notes,
            sourceCallId = sourceCallId,
            syncStatus = FollowUpEntity.SYNC_STATUS_PENDING_CREATE,
            updatedAtEpochMs = System.currentTimeMillis()
        )

        // 1. Local-first durable persistence
        followUpDao.insert(entity)

        // 2. Schedule local reminder alarm/worker
        scheduleReminder(entity)

        // 3. Opportunistic server sync
        tryPushPendingCreate(entity)

        return AppResult.Success(entity)
    }

    override suspend fun completeFollowUp(
        id: String,
        completionNotes: String
    ): AppResult<Unit> {
        // 1. Mark completed in Room
        followUpDao.markCompleted(id, FollowUpEntity.SYNC_STATUS_PENDING_COMPLETE)

        // 2. Cancel scheduled reminder & dismiss notification if present
        cancelReminder(id)
        LeadsNotificationManager.dismissFollowUpReminderNotification(context, id)

        // 3. Opportunistic server sync
        try {
            val response = followUpApiService.completeFollowUp(
                id = id,
                request = CompleteFollowUpRequest(completionNotes = completionNotes)
            )
            if (response.isSuccessful && response.body()?.success == true) {
                val existing = followUpDao.getById(id)
                if (existing != null) {
                    followUpDao.update(existing.copy(syncStatus = FollowUpEntity.SYNC_STATUS_SYNCED))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Offline complete recorded for $id, will sync on next cycle: ${e.message}")
        }

        return AppResult.Success(Unit)
    }

    override suspend fun rescheduleFollowUp(
        id: String,
        newScheduledAtEpochMs: Long,
        notes: String
    ): AppResult<Unit> {
        // 1. Update in Room
        followUpDao.reschedule(id, newScheduledAtEpochMs, notes, FollowUpEntity.SYNC_STATUS_PENDING_UPDATE)

        val updated = followUpDao.getById(id)
        if (updated != null) {
            // 2. Reschedule reminder alarm
            scheduleReminder(updated)
        }

        // 3. Opportunistic server sync
        try {
            val isoDate = isoFormat.format(Date(newScheduledAtEpochMs))
            val response = followUpApiService.updateFollowUp(
                id = id,
                request = UpdateFollowUpRequest(scheduledAt = isoDate, notes = notes)
            )
            if (response.isSuccessful && response.body()?.success == true) {
                if (updated != null) {
                    followUpDao.update(updated.copy(syncStatus = FollowUpEntity.SYNC_STATUS_SYNCED))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Offline reschedule recorded for $id, will sync on next cycle: ${e.message}")
        }

        return AppResult.Success(Unit)
    }

    override suspend fun syncFollowUps(): AppResult<Int> {
        var syncedChanges = 0

        // 1. Push offline pending changes first
        val pendingSync = followUpDao.getPendingSyncFollowUps()
        for (item in pendingSync) {
            try {
                when (item.syncStatus) {
                    FollowUpEntity.SYNC_STATUS_PENDING_CREATE -> {
                        val serverId = tryPushPendingCreate(item)
                        if (serverId != null) syncedChanges++
                    }
                    FollowUpEntity.SYNC_STATUS_PENDING_UPDATE -> {
                        val isoDate = isoFormat.format(Date(item.scheduledAtEpochMs))
                        val res = followUpApiService.updateFollowUp(
                            id = item.id,
                            request = UpdateFollowUpRequest(scheduledAt = isoDate, notes = item.notes)
                        )
                        if (res.isSuccessful && res.body()?.success == true) {
                            followUpDao.update(item.copy(syncStatus = FollowUpEntity.SYNC_STATUS_SYNCED))
                            syncedChanges++
                        }
                    }
                    FollowUpEntity.SYNC_STATUS_PENDING_COMPLETE -> {
                        val res = followUpApiService.completeFollowUp(
                            id = item.id,
                            request = CompleteFollowUpRequest(completionNotes = item.notes)
                        )
                        if (res.isSuccessful && res.body()?.success == true) {
                            followUpDao.update(item.copy(syncStatus = FollowUpEntity.SYNC_STATUS_SYNCED))
                            syncedChanges++
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error syncing pending follow-up ${item.id}: ${e.message}")
            }
        }

        // 2. Fetch remote follow-ups and reconcile
        try {
            val response = followUpApiService.getFollowUps(status = "pending")
            if (response.isSuccessful && response.body()?.success == true) {
                val remoteList = response.body()?.items.orEmpty()
                val incomingEntities = remoteList.map { dto ->
                    val epochMs = try {
                        isoFormat.parse(dto.scheduledAt)?.time ?: System.currentTimeMillis()
                    } catch (e: Exception) {
                        System.currentTimeMillis()
                    }

                    FollowUpEntity(
                        id = dto.id ?: dto.mongoId ?: UUID.randomUUID().toString(),
                        leadId = dto.leadId ?: dto.lead?.id ?: dto.lead?.mongoId,
                        leadName = dto.lead?.name ?: "",
                        leadPhone = dto.lead?.phone ?: "",
                        title = dto.title.ifBlank { "Follow-up call" },
                        type = dto.type,
                        scheduledAtEpochMs = epochMs,
                        status = dto.status,
                        priority = dto.priority,
                        notes = dto.notes,
                        assignedTo = dto.assignedTo,
                        sourceCallId = dto.sourceCallId,
                        syncStatus = FollowUpEntity.SYNC_STATUS_SYNCED,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                }

                // Preserve local items with pending offline mutations
                val localPendingMap = pendingSync.associateBy { it.id }
                val validRemoteIds = mutableListOf<String>()

                for (remoteEntity in incomingEntities) {
                    validRemoteIds.add(remoteEntity.id)
                    val local = localPendingMap[remoteEntity.id]
                    if (local == null) {
                        // Check if we have an existing local record matching sourceCallId or (leadId + scheduledAt window +-60s)
                        val existingMatch = if (!remoteEntity.sourceCallId.isNullOrBlank()) {
                            followUpDao.getBySourceCallId(remoteEntity.sourceCallId)
                        } else if (!remoteEntity.leadId.isNullOrBlank()) {
                            followUpDao.findPendingByLeadAndTime(
                                remoteEntity.leadId,
                                remoteEntity.scheduledAtEpochMs - 60000L,
                                remoteEntity.scheduledAtEpochMs + 60000L
                            )
                        } else null

                        if (existingMatch != null && existingMatch.id != remoteEntity.id) {
                            cancelReminder(existingMatch.id)
                            followUpDao.deleteById(existingMatch.id)
                        }
                        followUpDao.insert(remoteEntity)
                        scheduleReminder(remoteEntity)
                    }
                }

                syncedChanges += incomingEntities.size
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull remote follow-ups: ${e.message}")
            if (syncedChanges == 0) {
                return AppResult.Failure(AppError.NetworkError("Failed to synchronize follow-ups: ${e.message}"))
            }
        }

        return AppResult.Success(syncedChanges)
    }

    private suspend fun tryPushPendingCreate(entity: FollowUpEntity): String? {
        val leadId = entity.leadId
        if (leadId.isNullOrBlank()) return null

        return try {
            val isoDate = isoFormat.format(Date(entity.scheduledAtEpochMs))
            val response = followUpApiService.createFollowUp(
                CreateFollowUpRequest(
                    leadId = leadId,
                    title = entity.title,
                    type = entity.type,
                    scheduledAt = isoDate,
                    priority = entity.priority,
                    notes = entity.notes,
                    sourceCallId = entity.sourceCallId,
                    idempotencyKey = "fu_idemp_${entity.id}"
                )
            )

            if (response.isSuccessful && response.body()?.success == true) {
                val serverDto = response.body()?.data
                val serverId = serverDto?.id ?: serverDto?.mongoId
                if (!serverId.isNullOrBlank() && serverId != entity.id) {
                    // Replaced temporary local ID with authoritative server ID
                    followUpDao.deleteById(entity.id)
                    val serverEntity = entity.copy(id = serverId, syncStatus = FollowUpEntity.SYNC_STATUS_SYNCED)
                    followUpDao.insert(serverEntity)
                    scheduleReminder(serverEntity)
                    serverId
                } else {
                    followUpDao.update(entity.copy(syncStatus = FollowUpEntity.SYNC_STATUS_SYNCED))
                    entity.id
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Offline create pending for ${entity.id}: ${e.message}")
            null
        }
    }

    private fun scheduleReminder(followUp: FollowUpEntity) {
        if (followUp.status != FollowUpEntity.STATUS_PENDING) return

        val delayMs = maxOf(0L, followUp.scheduledAtEpochMs - System.currentTimeMillis())

        val workRequest = OneTimeWorkRequestBuilder<FollowUpReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    FollowUpReminderWorker.KEY_FOLLOW_UP_ID to followUp.id,
                    FollowUpReminderWorker.KEY_LEAD_ID to (followUp.leadId ?: ""),
                    FollowUpReminderWorker.KEY_LEAD_NAME to followUp.leadName,
                    FollowUpReminderWorker.KEY_LEAD_PHONE to followUp.leadPhone,
                    FollowUpReminderWorker.KEY_TITLE to followUp.title,
                    FollowUpReminderWorker.KEY_NOTES to followUp.notes
                )
            )
            .addTag("${FollowUpReminderWorker.WORK_TAG_PREFIX}${followUp.id}")
            .build()

        workManager.enqueueUniqueWork(
            "${FollowUpReminderWorker.WORK_TAG_PREFIX}${followUp.id}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    private fun cancelReminder(followUpId: String) {
        workManager.cancelUniqueWork("${FollowUpReminderWorker.WORK_TAG_PREFIX}$followUpId")
    }

    private fun getDayBounds(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfToday = cal.timeInMillis

        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val endOfToday = cal.timeInMillis

        return Pair(startOfToday, endOfToday)
    }

    companion object {
        private const val TAG = "FollowUpRepository"
    }
}
