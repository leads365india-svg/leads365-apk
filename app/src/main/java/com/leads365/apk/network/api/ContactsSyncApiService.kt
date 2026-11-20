package com.leads365.apk.network.api

import com.leads365.apk.network.models.SyncPhoneContactsRequest
import com.leads365.apk.network.models.SyncPhoneContactsResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface ContactsSyncApiService {

    @POST("integrations/whatsapp-qr/sync-contacts")
    suspend fun syncContacts(
        @Body request: SyncPhoneContactsRequest
    ): SyncPhoneContactsResponse
}
