package com.leads365.apk.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "leads",
    indices = [
        Index(value = ["normalizedNumber"], unique = true),
        Index(value = ["updatedAtEpochMs"]),
        Index(value = ["status"])
    ]
)
data class LeadEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val rawNumber: String,
    val normalizedNumber: String,
    val status: String = "New",
    val nextFollowUpEpochMs: Long? = null,
    val isDoNotRecord: Boolean = false,
    val notes: String = "",
    val assignedAgentId: String? = null,
    val enabled: Boolean = true,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
