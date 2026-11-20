package com.leads365.apk.contacts

sealed class ContactsSyncResult {
    data class Success(
        val syncedCount: Int,
        val totalContacts: Int,
        val hash: String
    ) : ContactsSyncResult()

    data class Skipped(
        val reason: String,
        val hash: String? = null
    ) : ContactsSyncResult()

    data class Failed(
        val error: String
    ) : ContactsSyncResult()
}
