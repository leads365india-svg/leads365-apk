package com.leads365.apk.notifications

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.leads365.apk.data.repository.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FcmRegistrationManager @Inject constructor(
    private val authRepository: AuthRepository
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Checks if Firebase is initialized and attempts to fetch/register FCM token.
     * Never crashes if google-services.json or Firebase is unconfigured.
     */
    fun checkAndRegisterToken(context: Context) {
        scope.launch {
            try {
                // Check if Firebase is initialized
                if (FirebaseApp.getApps(context).isEmpty()) {
                    Log.w(
                        TAG,
                        "Firebase is not initialized (google-services.json missing). FCM registration deferred."
                    )
                    return@launch
                }

                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                        return@addOnCompleteListener
                    }

                    val token = task.result
                    if (!token.isNullOrBlank()) {
                        Log.i(TAG, "FCM token retrieved successfully")
                        scope.launch {
                            authRepository.updatePushToken(token)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Firebase Cloud Messaging unavailable on this device or configuration: ${e.message}"
                )
            }
        }
    }

    companion object {
        const val TAG = "FcmRegistrationManager"
    }
}
