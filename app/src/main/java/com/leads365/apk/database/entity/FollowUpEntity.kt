package com.leads365.apk.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "follow_ups",
    indices = [
        Index(value = ["status"]),
        Index(value = ["scheduledAtEpochMs"]),
        Index(value = ["leadId"]),
        Index(value = ["sourceCallId"])
    ]
)
data class FollowUpEntity(
    @PrimaryKey
    val id: String,
    val leadId: String? = null,
    val leadName: String = "",
    val leadPhone: String = "",
    val title: String = "",
    val type: String = "call",
    val scheduledAtEpochMs: Long,
    val status: String = STATUS_PENDING,
    val priority: String = PRIORITY_MEDIUM,
    val notes: String = "",
    val assignedTo: String = "",
    val sourceCallId: String? = null,
    val syncStatus: String = SYNC_STATUS_SYNCED,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_CANCELLED = "cancelled"

        const val PRIORITY_LOW = "low"
        const val PRIORITY_MEDIUM = "medium"
        const val PRIORITY_HIGH = "high"

        const val SYNC_STATUS_SYNCED = "SYNCED"
        const val SYNC_STATUS_PENDING_CREATE = "PENDING_CREATE"
        const val SYNC_STATUS_PENDING_UPDATE = "PENDING_UPDATE"
        const val SYNC_STATUS_PENDING_COMPLETE = "PENDING_COMPLETE"
    }
}
