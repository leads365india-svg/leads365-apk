package com.leads365.apk.notifications

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.leads365.apk.data.repository.AuthRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LeadsFirebaseMessagingService : FirebaseMessagingService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface FcmServiceEntryPoint {
        fun authRepository(): AuthRepository
        fun pushMessageProcessor(): PushMessageProcessor
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "New FCM push registration token received from Firebase")

        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                FcmServiceEntryPoint::class.java
            )
            val authRepo = entryPoint.authRepository()

            // Update token locally and send to backend if authenticated
            serviceScope.launch {
                authRepo.updatePushToken(token)
                Log.d(TAG, "Push token successfully dispatched to backend registration endpoint")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling new FCM token: ${e.message}", e)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.i(TAG, "FCM message received: messageId=${remoteMessage.messageId}")

        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                FcmServiceEntryPoint::class.java
            )
            val processor = entryPoint.pushMessageProcessor()

            val dataPayload = remoteMessage.data
            if (dataPayload.isNotEmpty()) {
                processor.processPushPayload(applicationContext, dataPayload)
            } else if (remoteMessage.notification != null) {
                // If notification-only payload without data dictionary
                val notif = remoteMessage.notification!!
                LeadsNotificationManager.showLeadAssignedNotification(
                    context = applicationContext,
                    leadId = "fcm_notif_${System.currentTimeMillis()}",
                    leadName = notif.title ?: "New Lead",
                    leadStatus = "New",
                    leadNotes = notif.body
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming FCM message: ${e.message}", e)
        }
    }

    companion object {
        const val TAG = "LeadsFirebaseMsg"
    }
}
