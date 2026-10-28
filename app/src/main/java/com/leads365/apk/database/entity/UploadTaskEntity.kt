package com.leads365.apk.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "upload_tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["callId"]),
        Index(value = ["updatedAtEpochMs"])
    ]
)
data class UploadTaskEntity(
    @PrimaryKey
    val id: String,
    val callId: String? = null,
    val leadId: String? = null,
    val localFileUri: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val sha256Hash: String? = null,
    val status: String = STATUS_PENDING,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val lastError: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_IN_PROGRESS = "IN_PROGRESS"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"

        // Official Phase 8 Upload State Machine
        const val STATE_DETECTED_LEAD_CALL = "DETECTED_LEAD_CALL"
        const val STATE_CALL_REPORTED = "CALL_REPORTED"
        const val STATE_AWAITING_FILE = "AWAITING_FILE"
        const val STATE_FILE_FOUND = "FILE_FOUND"
        const val STATE_UPLOADING = "UPLOADING"
        const val STATE_UPLOADED = "UPLOADED"

        // Side states
        const val STATE_RETRY_WAIT = "RETRY_WAIT"
        const val STATE_NO_FILE_FOUND = "NO_FILE_FOUND"
        const val STATE_AMBIGUOUS = "AMBIGUOUS"
        const val STATE_SKIPPED_DNR = "SKIPPED_DNR"
        const val STATE_SKIPPED_NOT_LEAD = "SKIPPED_NOT_LEAD"
        const val STATE_REJECTED_BY_SERVER = "REJECTED_BY_SERVER"
        const val STATE_FAILED_PERMANENT = "FAILED_PERMANENT"
    }
}
