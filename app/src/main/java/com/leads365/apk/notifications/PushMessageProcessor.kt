package com.leads365.apk.notifications

import android.content.Context
import android.util.Log
import com.leads365.apk.work.WorkManagerScheduler
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PushMessageProcessor @Inject constructor(
    private val workManagerScheduler: WorkManagerScheduler
) {

    // LRU / TTL cache for deduplicating repeated FCM events within 15 seconds
    private val recentEventTimestamps = ConcurrentHashMap<String, Long>()

    /**
     * Process an incoming push data payload.
     * Returns true if the message was handled as a recognized Phase 10 event.
     */
    fun processPushPayload(context: Context, data: Map<String, String>): Boolean {
        val eventType = data["eventType"] ?: data["type"] ?: return false
        val entityId = data["leadId"] ?: data["id"] ?: data["followUpId"] ?: return false
        val leadId = data["leadId"] ?: data["id"] ?: entityId

        // 1. Deduplication check
        val dedupeKey = "${eventType}_$entityId"
        val now = System.currentTimeMillis()
        val lastSeen = recentEventTimestamps[dedupeKey]
        if (lastSeen != null && now - lastSeen < DEDUPE_WINDOW_MS) {
            Log.d(TAG, "Ignoring duplicate push event: $dedupeKey")
            return false
        }
        recentEventTimestamps[dedupeKey] = now

        // Clean up stale cache entries
        cleanStaleCache(now)

        return when (eventType) {
            EVENT_LEAD_ASSIGNED, "LEAD_ASSIGNED" -> {
                val leadName = data["name"] ?: "Assigned Lead"
                val leadStatus = data["status"] ?: "New"
                val leadNotes = data["notes"]

                Log.i(TAG, "Processing LEAD_ASSIGNED event for lead: $leadId ($leadName)")

                // Trigger background lead delta sync immediately to update Room database
                workManagerScheduler.triggerOneTimeLeadSync()

                // Display lead assignment notification
                LeadsNotificationManager.showLeadAssignedNotification(
                    context = context,
                    leadId = leadId,
                    leadName = leadName,
                    leadStatus = leadStatus,
                    leadNotes = leadNotes
                )
                true
            }
            EVENT_LEAD_REASSIGNED_AWAY, "LEAD_REASSIGNED_AWAY" -> {
                Log.i(TAG, "Processing LEAD_REASSIGNED_AWAY event for lead: $leadId")

                // Trigger background delta sync so local Room database deletes tombstoned lead
                workManagerScheduler.triggerOneTimeLeadSync()

                // Display reassigned away notification (links to My Leads, not the removed lead)
                LeadsNotificationManager.showLeadReassignedAwayNotification(
                    context = context,
                    leadId = leadId
                )
                true
            }
            EVENT_CALLBACK_SCHEDULED, "CALLBACK_SCHEDULED" -> {
                val followUpId = data["followUpId"] ?: entityId
                val leadName = data["leadName"] ?: data["name"] ?: "Customer"
                val leadPhone = data["leadPhone"] ?: data["phone"] ?: ""
                val title = data["title"] ?: "AI Callback Scheduled: $leadName"
                val notes = data["notes"] ?: "Callback scheduled via AI"

                Log.i(TAG, "Processing CALLBACK_SCHEDULED event for lead: $leadId ($leadName)")
                workManagerScheduler.triggerOneTimeLeadSync()

                LeadsNotificationManager.showFollowUpReminderNotification(
                    context = context,
                    followUpId = followUpId,
                    leadId = leadId,
                    leadName = leadName,
                    leadPhone = leadPhone,
                    title = title,
                    notes = notes
                )
                true
            }
            EVENT_CALLBACK_REMINDER, "CALLBACK_REMINDER", EVENT_CALLBACK_DUE, "CALLBACK_DUE" -> {
                val followUpId = data["followUpId"] ?: entityId
                val leadName = data["leadName"] ?: data["name"] ?: "Customer"
                val leadPhone = data["leadPhone"] ?: data["phone"] ?: ""
                val isDue = eventType == EVENT_CALLBACK_DUE || eventType == "CALLBACK_DUE"
                val defaultTitle = if (isDue) "Callback Due Now: $leadName" else "Upcoming Callback in 5 mins: $leadName"
                val title = data["title"]?.takeIf { it.isNotBlank() } ?: defaultTitle
                val notes = data["notes"] ?: (if (isDue) "Callback is due now" else "Callback in 5 minutes")

                Log.i(TAG, "Processing ${eventType} event for lead: $leadId ($leadName)")
                workManagerScheduler.triggerOneTimeLeadSync()

                LeadsNotificationManager.showFollowUpReminderNotification(
                    context = context,
                    followUpId = followUpId,
                    leadId = leadId,
                    leadName = leadName,
                    leadPhone = leadPhone,
                    title = title,
                    notes = notes
                )
                true
            }
            EVENT_CALLBACK_RESCHEDULED, "CALLBACK_RESCHEDULED" -> {
                val followUpId = data["followUpId"] ?: entityId
                val leadName = data["leadName"] ?: data["name"] ?: "Customer"
                val leadPhone = data["leadPhone"] ?: data["phone"] ?: ""
                val title = data["title"] ?: "Callback Rescheduled: $leadName"
                val notes = data["notes"] ?: "Callback time updated"

                Log.i(TAG, "Processing CALLBACK_RESCHEDULED event for lead: $leadId ($leadName)")
                workManagerScheduler.triggerOneTimeLeadSync()

                LeadsNotificationManager.showFollowUpReminderNotification(
                    context = context,
                    followUpId = followUpId,
                    leadId = leadId,
                    leadName = leadName,
                    leadPhone = leadPhone,
                    title = title,
                    notes = notes
                )
                true
            }
            else -> {
                Log.d(TAG, "Unrecognized push event type: $eventType")
                false
            }
        }
    }

    private fun cleanStaleCache(now: Long) {
        if (recentEventTimestamps.size > 100) {
            val iterator = recentEventTimestamps.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > DEDUPE_WINDOW_MS) {
                    iterator.remove()
                }
            }
        }
    }

    companion object {
        const val TAG = "PushMessageProcessor"
        const val EVENT_LEAD_ASSIGNED = "LEAD_ASSIGNED"
        const val EVENT_LEAD_REASSIGNED_AWAY = "LEAD_REASSIGNED_AWAY"
        const val EVENT_CALLBACK_SCHEDULED = "EVENT_CALLBACK_SCHEDULED"
        const val EVENT_CALLBACK_REMINDER = "EVENT_CALLBACK_REMINDER"
        const val EVENT_CALLBACK_DUE = "EVENT_CALLBACK_DUE"
        const val EVENT_CALLBACK_RESCHEDULED = "EVENT_CALLBACK_RESCHEDULED"
        private const val DEDUPE_WINDOW_MS = 15_000L
    }
}
