package com.leads365.apk.network.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EncryptedTokenProvider(
    private val context: Context
) : TokenProvider {

    private val prefs: SharedPreferences by lazy {
        initPreferences()
    }

    private fun initPreferences(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            android.util.Log.w("EncryptedTokenProvider", "Keystore initialization fallback: ${e.message}")
            context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun safeGetString(key: String, defaultValue: String? = null): String? {
        return try {
            prefs.getString(key, defaultValue)
        } catch (e: Exception) {
            android.util.Log.w("EncryptedTokenProvider", "Error reading key $key from encrypted prefs: ${e.message}")
            try {
                context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE).getString(key, defaultValue)
            } catch (ignored: Exception) {
                defaultValue
            }
        }
    }

    private fun safeEdit(block: SharedPreferences.Editor.() -> Unit) {
        try {
            val editor = prefs.edit()
            editor.block()
            val committed = editor.commit()
            if (!committed) {
                editor.apply()
            }
        } catch (e: Exception) {
            android.util.Log.e("EncryptedTokenProvider", "Encrypted prefs commit failed, writing to fallback: ${e.message}")
            try {
                val fallbackEditor = context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE).edit()
                fallbackEditor.block()
                fallbackEditor.commit()
            } catch (ignored: Exception) {
            }
        }
    }

    override fun getAccessToken(): String? = safeGetString(KEY_ACCESS_TOKEN, null)

    override fun getRefreshToken(): String? = safeGetString(KEY_REFRESH_TOKEN, null)

    override fun saveTokens(accessToken: String, refreshToken: String) {
        safeEdit {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
        }
    }

    fun saveSessionInfo(
        userId: String,
        userName: String,
        userEmail: String,
        agencyName: String?,
        agencyAccountId: String?
    ) {
        safeEdit {
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_NAME, userName)
            putString(KEY_USER_EMAIL, userEmail)
            putString(KEY_AGENCY_NAME, agencyName)
            putString(KEY_AGENCY_ACCOUNT_ID, agencyAccountId)
        }
    }

    fun saveDeviceId(deviceId: String) {
        safeEdit {
            putString(KEY_DEVICE_ID, deviceId)
        }
    }

    fun getDeviceId(): String? = safeGetString(KEY_DEVICE_ID, null)

    override fun getAgencyAccountId(): String? = safeGetString(KEY_AGENCY_ACCOUNT_ID, null)

    fun getOrCreateInstallId(): String {
        val existing = safeGetString(KEY_INSTALL_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val newId = "inst_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        safeEdit {
            putString(KEY_INSTALL_ID, newId)
        }
        return newId
    }

    override fun getDeviceIdOrInstallId(): String = getDeviceId() ?: getOrCreateInstallId()

    fun saveFcmToken(token: String) {
        safeEdit {
            putString(KEY_FCM_TOKEN, token)
        }
    }

    fun getFcmToken(): String? = safeGetString(KEY_FCM_TOKEN, null)

    fun clearFcmToken() {
        safeEdit {
            remove(KEY_FCM_TOKEN)
        }
    }

    fun getUserId(): String? = safeGetString(KEY_USER_ID, null)
    fun getUserName(): String? = safeGetString(KEY_USER_NAME, null)
    fun getUserEmail(): String? = safeGetString(KEY_USER_EMAIL, null)
    fun getAgencyName(): String? = safeGetString(KEY_AGENCY_NAME, null)

    override fun clearTokens() {
        safeEdit {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
        }
    }

    fun clearAllSession() {
        safeEdit {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_USER_ID)
            remove(KEY_USER_NAME)
            remove(KEY_USER_EMAIL)
            remove(KEY_AGENCY_NAME)
            remove(KEY_AGENCY_ACCOUNT_ID)
            remove(KEY_DEVICE_ID)
            remove(KEY_FCM_TOKEN)
            // KEY_INSTALL_ID is intentionally preserved across sessions
        }
        try {
            val fallback = context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
            fallback.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_USER_ID)
                .remove(KEY_USER_NAME)
                .remove(KEY_USER_EMAIL)
                .remove(KEY_AGENCY_NAME)
                .remove(KEY_AGENCY_ACCOUNT_ID)
                .remove(KEY_DEVICE_ID)
                .remove(KEY_FCM_TOKEN)
                .apply()
        } catch (ignored: Exception) {}
    }

    companion object {
        private const val ENCRYPTED_PREFS_NAME = "leads365_encrypted_auth"
        private const val FALLBACK_PREFS_NAME = "leads365_auth_fallback"
        private const val KEY_ACCESS_TOKEN = "key_access_token"
        private const val KEY_REFRESH_TOKEN = "key_refresh_token"
        private const val KEY_USER_ID = "key_user_id"
        private const val KEY_USER_NAME = "key_user_name"
        private const val KEY_USER_EMAIL = "key_user_email"
        private const val KEY_AGENCY_NAME = "key_agency_name"
        private const val KEY_AGENCY_ACCOUNT_ID = "key_agency_account_id"
        private const val KEY_DEVICE_ID = "key_device_id"
        private const val KEY_INSTALL_ID = "key_install_id"
        private const val KEY_FCM_TOKEN = "key_fcm_token"
    }
}
