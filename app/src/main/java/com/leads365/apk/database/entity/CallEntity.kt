package com.leads365.apk.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "calls",
    indices = [
        Index(value = ["normalizedNumber"]),
        Index(value = ["startTimeEpochMs"]),
        Index(value = ["clientCallId"], unique = true)
    ]
)
data class CallEntity(
    @PrimaryKey
    val id: String,
    val clientCallId: String = id,
    val normalizedNumber: String,
    val rawNumber: String,
    val callType: Int,
    val startTimeEpochMs: Long,
    val durationSeconds: Long,
    val isLeadCall: Boolean = false,
    val leadId: String? = null,
    val simId: String? = null,
    val callOutcome: String? = null,
    val notes: String? = null,
    val nextFollowUpEpochMs: Long? = null,
    val syncStatus: String = "PENDING",
    val syncErrorMessage: String? = null,
    val hasRecording: Boolean = false,
    val recordingUri: String? = null,
    val recordingFileName: String? = null,
    val recordingFileSizeBytes: Long? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
