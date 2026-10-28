package com.leads365.apk.network.api

import com.leads365.apk.network.models.LeadSyncResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface LeadApiService {

    @GET("api/mobile/v1/leads/sync")
    suspend fun syncLeads(
        @Query("since") sinceTimestampEpochMs: Long? = null,
        @Query("limit") limit: Int = 100
    ): LeadSyncResponse
}
