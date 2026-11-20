package com.leads365.apk.network.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SyncPhoneContactsRequest(
    val phones: List<String> = emptyList(),
    val replaceAll: Boolean = false
)

@Serializable
data class SyncPhoneContactsData(
    @SerialName("syncedCount") val syncedCount: Int = 0,
    @SerialName("tenantId") val tenantId: String? = null
)

@Serializable
data class SyncPhoneContactsResponse(
    val success: Boolean = true,
    val data: SyncPhoneContactsData? = null,
    val message: String? = null,
    val error: String? = null
)
