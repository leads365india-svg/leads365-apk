package com.leads365.apk.network.api

import com.leads365.apk.network.models.CompleteFollowUpRequest
import com.leads365.apk.network.models.CreateFollowUpRequest
import com.leads365.apk.network.models.FollowUpActionResponse
import com.leads365.apk.network.models.FollowUpListResponse
import com.leads365.apk.network.models.UpdateFollowUpRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface FollowUpApiService {

    @GET("follow-ups")
    suspend fun getFollowUps(
        @Query("status") status: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50
    ): Response<FollowUpListResponse>

    @POST("follow-ups")
    suspend fun createFollowUp(
        @Body request: CreateFollowUpRequest
    ): Response<FollowUpActionResponse>

    @PATCH("follow-ups/{id}")
    suspend fun updateFollowUp(
        @Path("id") id: String,
        @Body request: UpdateFollowUpRequest
    ): Response<FollowUpActionResponse>

    @PATCH("follow-ups/{id}/complete")
    suspend fun completeFollowUp(
        @Path("id") id: String,
        @Body request: CompleteFollowUpRequest
    ): Response<FollowUpActionResponse>
}
