package com.leads365.apk.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.leads365.apk.R
import com.leads365.apk.ui.MainActivity

object LeadsNotificationManager {

    const val CHANNEL_ID_LEAD_ASSIGNMENTS = "leads_assignment_channel"
    const val CHANNEL_ID_CALLER_ID = "leads_caller_id_channel"
    const val CHANNEL_ID_FOLLOW_UP_REMINDERS = "leads_followup_reminder_channel"

    const val ACTION_VIEW_LEAD = "com.leads365.apk.ACTION_VIEW_LEAD"
    const val ACTION_VIEW_LEADS_LIST = "com.leads365.apk.ACTION_VIEW_LEADS_LIST"
    const val ACTION_VIEW_FOLLOW_UPS = "com.leads365.apk.ACTION_VIEW_FOLLOW_UPS"

    const val EXTRA_LEAD_ID = "extra_lead_id"
    const val EXTRA_FOLLOW_UP_ID = "extra_follow_up_id"
    const val EXTRA_DESTINATION = "extra_destination"

    private const val NOTIFICATION_ID_CALLER_ID = 2001
    private const val NOTIFICATION_ID_BASE_ASSIGNMENT = 3000
    private const val NOTIFICATION_ID_BASE_FOLLOW_UP = 4000

    /**
     * Initializes Android notification channels (Android 8.0+ / API 26+)
     */
    fun initChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val assignmentChannel = NotificationChannel(
                CHANNEL_ID_LEAD_ASSIGNMENTS,
                "Lead Assignments",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for newly assigned and reassigned leads"
                enableVibration(true)
                setShowBadge(true)
            }

            val callerIdChannel = NotificationChannel(
                CHANNEL_ID_CALLER_ID,
                "Caller Identification",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time caller card pop-up fallback for lead calls"
                enableVibration(true)
                setShowBadge(false)
            }

            val followUpChannel = NotificationChannel(
                CHANNEL_ID_FOLLOW_UP_REMINDERS,
                "Follow-up Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alarms and reminders for scheduled lead follow-up calls"
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            }

            notificationManager.createNotificationChannel(assignmentChannel)
            notificationManager.createNotificationChannel(callerIdChannel)
            notificationManager.createNotificationChannel(followUpChannel)
        }
    }

    /**
     * Display a notification when a new lead is assigned to the current agent.
     * Tapping opens MainActivity directly to the lead details.
     */
    fun showLeadAssignedNotification(
        context: Context,
        leadId: String,
        leadName: String,
        leadStatus: String,
        leadNotes: String? = null
    ) {
        initChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VIEW_LEAD
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_LEAD_ID, leadId)
            putExtra(EXTRA_DESTINATION, "leads")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            leadId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bodyText = if (!leadNotes.isNullOrBlank()) {
            "Status: $leadStatus • $leadNotes"
        } else {
            "Status: $leadStatus • Tap to open lead details"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_LEAD_ASSIGNMENTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("New Lead: $leadName")
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID_BASE_ASSIGNMENT + (leadId.hashCode() % 1000),
                notification
            )
        } catch (e: SecurityException) {
            // Android 13 POST_NOTIFICATIONS permission not granted
        }
    }

    /**
     * Display a notification when a lead is reassigned away from the current agent.
     * Tapping opens MainActivity to the My Leads list (not the removed lead).
     */
    fun showLeadReassignedAwayNotification(
        context: Context,
        leadId: String
    ) {
        initChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VIEW_LEADS_LIST
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DESTINATION, "leads")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            ("reassign_$leadId").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_LEAD_ASSIGNMENTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Lead Reassigned")
            .setContentText("A lead was reassigned away from your queue")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID_BASE_ASSIGNMENT + (("reassign_$leadId").hashCode() % 1000),
                notification
            )
        } catch (e: SecurityException) {
            // Android 13 POST_NOTIFICATIONS permission not granted
        }
    }

    /**
     * Heads-up notification fallback when overlay permission is denied or unavailable.
     * Shows lead name, status, and last note, with an action to view lead details.
     */
    fun showCallerIdHeadsUpNotification(
        context: Context,
        leadId: String,
        leadName: String,
        leadStatus: String,
        lastNote: String?,
        normalizedNumber: String
    ) {
        initChannels(context)

        val openLeadIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VIEW_LEAD
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_LEAD_ID, leadId)
            putExtra(EXTRA_DESTINATION, "leads")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            leadId.hashCode(),
            openLeadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val detailText = if (!lastNote.isNullOrBlank()) {
            "Status: $leadStatus • Note: $lastNote"
        } else {
            "Status: $leadStatus • $normalizedNumber"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_CALLER_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Lead: $leadName ($leadStatus)")
            .setContentText(detailText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_view,
                "Open Lead",
                pendingIntent
            )
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_CALLER_ID, notification)
        } catch (e: SecurityException) {
            // Android 13 POST_NOTIFICATIONS permission not granted
        }
    }

    /**
     * Dismisses the caller identification heads-up notification when call terminates.
     */
    fun dismissCallerIdNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_CALLER_ID)
        } catch (ignored: Exception) {}
    }

    /**
     * Dispatches a local reminder notification when a follow-up is due.
     */
    fun showFollowUpReminderNotification(
        context: Context,
        followUpId: String,
        leadId: String?,
        leadName: String,
        leadPhone: String,
        title: String,
        notes: String? = null
    ) {
        initChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VIEW_FOLLOW_UPS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_FOLLOW_UP_ID, followUpId)
            if (!leadId.isNullOrBlank()) {
                putExtra(EXTRA_LEAD_ID, leadId)
            }
            putExtra(EXTRA_DESTINATION, "followups")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            followUpId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentTitle = if (title.isNotBlank()) "Follow-up: $title" else "Follow-up Reminder"
        val bodyText = if (leadName.isNotBlank()) {
            "Lead: $leadName • $leadPhone${if (!notes.isNullOrBlank()) " • $notes" else ""}"
        } else {
            notes?.takeIf { it.isNotBlank() } ?: "Scheduled follow-up is due now"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_FOLLOW_UP_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(contentTitle)
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            val notificationId = NOTIFICATION_ID_BASE_FOLLOW_UP + Math.abs(followUpId.hashCode() % 1000)
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // Android 13 POST_NOTIFICATIONS permission not granted
        }
    }

    fun dismissFollowUpReminderNotification(context: Context, followUpId: String) {
        try {
            val notificationId = NOTIFICATION_ID_BASE_FOLLOW_UP + Math.abs(followUpId.hashCode() % 1000)
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (ignored: Exception) {}
    }
}
