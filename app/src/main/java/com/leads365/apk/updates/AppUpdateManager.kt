package com.leads365.apk.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.leads365.apk.BuildConfig
import com.leads365.apk.network.models.AppVersionResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

sealed class UpdateEvaluation {
    object UpToDate : UpdateEvaluation()
    data class UpdateAvailable(
        val minVersionCode: Int,
        val minVersionName: String,
        val isForced: Boolean,
        val downloadUrl: String?,
        val message: String
    ) : UpdateEvaluation()
}

/**
 * Evaluates backend version policy and safely directs users to verified HTTPS
 * store/portal update URLs without in-app APK sideloading.
 */
@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @Suppress("UNUSED_PARAMETER") private val okHttpClient: OkHttpClient
) {

    fun evaluateVersion(response: AppVersionResponse, currentVersionCode: Int = BuildConfig.VERSION_CODE): UpdateEvaluation {
        val isOutdated = currentVersionCode < response.minVersionCode
        val isForced = response.isUpdateRequired || isOutdated

        return if (isForced || (!response.updateUrl.isNullOrBlank() && isOutdated)) {
            UpdateEvaluation.UpdateAvailable(
                minVersionCode = response.minVersionCode,
                minVersionName = response.minVersionName,
                isForced = isForced,
                downloadUrl = response.updateUrl,
                message = response.message.ifBlank {
                    if (isForced) "A mandatory update is required to continue using Leads365."
                    else "A recommended update is available for Leads365."
                }
            )
        } else {
            UpdateEvaluation.UpToDate
        }
    }

    /**
     * Builds an ACTION_VIEW intent only for verified HTTPS update URLs.
     * Returns null for missing, blank, or non-HTTPS URLs.
     */
    fun createOpenUpdateUrlIntent(updateUrl: String?): Intent? {
        val normalized = updateUrl?.trim().orEmpty()
        if (!normalized.startsWith("https://", ignoreCase = true)) {
            return null
        }
        val uri = Uri.parse(normalized)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            return null
        }
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
