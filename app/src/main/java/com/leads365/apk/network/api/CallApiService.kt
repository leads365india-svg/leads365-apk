package com.leads365.apk.network.api

import com.leads365.apk.network.models.CallReportRequest
import com.leads365.apk.network.models.CallReportResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface CallApiService {

    @POST("mobile/v1/calls")
    suspend fun reportCall(
        @Body request: CallReportRequest
    ): CallReportResponse
}
