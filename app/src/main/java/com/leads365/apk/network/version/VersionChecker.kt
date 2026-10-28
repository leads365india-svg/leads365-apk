package com.leads365.apk.network.version

import com.leads365.apk.network.models.AppVersionResponse

sealed class VersionStatus {
    object UpToDate : VersionStatus()
    data class UpdateRecommended(val message: String, val updateUrl: String?) : VersionStatus()
    data class UpdateRequired(val message: String, val updateUrl: String?) : VersionStatus()
}

class VersionChecker {

    fun evaluate(currentVersionCode: Int, response: AppVersionResponse): VersionStatus {
        return when {
            response.isUpdateRequired || currentVersionCode < response.minVersionCode -> {
                VersionStatus.UpdateRequired(
                    message = response.message.ifBlank { "A required update is available for Leads365." },
                    updateUrl = response.updateUrl
                )
            }
            else -> VersionStatus.UpToDate
        }
    }
}
