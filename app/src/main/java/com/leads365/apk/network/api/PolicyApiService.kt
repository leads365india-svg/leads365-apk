package com.leads365.apk.network.api

import com.leads365.apk.network.models.AutoLeadPolicyRequest
import com.leads365.apk.network.models.AutoLeadPolicyResponse
import com.leads365.apk.network.models.PauseSyncRequest
import com.leads365.apk.network.models.PauseSyncResponse
import com.leads365.apk.network.models.PolicyResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface PolicyApiService {

    @GET("mobile/v1/policy")
    suspend fun getPolicy(): PolicyResponse

    @POST("mobile/v1/policy/pause")
    suspend fun setPauseSyncState(
        @Body request: PauseSyncRequest
    ): PauseSyncResponse

    @POST("mobile/v1/policy/auto-lead")
    suspend fun setAutoLeadPolicy(
        @Body request: AutoLeadPolicyRequest
    ): AutoLeadPolicyResponse
}
