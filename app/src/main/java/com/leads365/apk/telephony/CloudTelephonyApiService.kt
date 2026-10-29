package com.leads365.apk.telephony

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface CloudTelephonyApiService {

    @POST("telephony/call")
    suspend fun initiateCall(
        @Body request: InitiateCloudCallRequest
    ): Response<CloudCallStatusResponse>

    @GET("telephony/call/{callId}/status")
    suspend fun getCallStatus(
        @Path("callId") callId: String
    ): Response<CloudCallStatusResponse>
}
