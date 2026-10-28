package com.leads365.apk.network.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MobileLeadDto(
    val id: String,
    val name: String,
    val phone: String,
    @SerialName("normalized_phone") val normalizedPhone: String? = null,
    val status: String = "New",
    @SerialName("next_followup_at") val nextFollowUpAt: Long? = null,
    val notes: String? = null,
    @SerialName("is_do_not_record") val isDoNotRecord: Boolean = false,
    @SerialName("assigned_to_agent_id") val assignedToAgentId: String? = null,
    @SerialName("updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class LeadSyncData(
    val leads: List<MobileLeadDto> = emptyList(),
    @SerialName("deleted_lead_ids") val deletedLeadIds: List<String> = emptyList(),
    @SerialName("full_sync_required") val fullSyncRequired: Boolean = false
)

@Serializable
data class LeadSyncResponse(
    val success: Boolean = true,
    @SerialName("server_time") val serverTime: Long = System.currentTimeMillis(),
    @SerialName("has_more") val hasMore: Boolean = false,
    val data: LeadSyncData? = null,
    val error: String? = null,
    val errorCode: String? = null
)
