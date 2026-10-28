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
        val leadId = data["leadId"] ?: data["id"] ?: return false

        // 1. Deduplication check
        val dedupeKey = "${eventType}_$leadId"
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
            EVENT_LEAD_ASSIGNED -> {
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
            EVENT_LEAD_REASSIGNED_AWAY -> {
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
        private const val DEDUPE_WINDOW_MS = 15_000L
    }
}
