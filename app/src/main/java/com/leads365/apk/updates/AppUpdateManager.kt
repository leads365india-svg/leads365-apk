package com.leads365.apk.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.leads365.apk.BuildConfig
import com.leads365.apk.network.models.AppVersionResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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

sealed class DownloadAndVerifyResult {
    data class Success(val apkFile: File, val computedSha256: String) : DownloadAndVerifyResult()
    data class VerificationFailed(val reason: String) : DownloadAndVerifyResult()
    data class Failure(val error: String) : DownloadAndVerifyResult()
}

sealed class InstallTriggerResult {
    object Launched : InstallTriggerResult()
    object PermissionRequired : InstallTriggerResult()
    data class Failure(val error: String) : InstallTriggerResult()
}

@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
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

    suspend fun downloadAndVerifyApk(
        downloadUrl: String,
        expectedSha256: String? = null,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)? = null
    ): DownloadAndVerifyResult = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val targetFile = File(updateDir, "leads365-update.apk")
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val request = Request.Builder().url(downloadUrl).build()
            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext DownloadAndVerifyResult.Failure("HTTP ${response.code} downloading update")
            }

            val body = response.body ?: return@withContext DownloadAndVerifyResult.Failure("Empty update response body")
            val totalBytes = body.contentLength()

            val digest = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        onProgress?.invoke(totalRead, totalBytes)
                    }
                    output.flush()
                }
            }

            val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }

            // 1. Verify SHA-256 hash if server provided one
            if (!expectedSha256.isNullOrBlank() && !computedSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
                targetFile.delete()
                return@withContext DownloadAndVerifyResult.VerificationFailed(
                    "SHA-256 mismatch. Expected: $expectedSha256, Computed: $computedSha256"
                )
            }

            // 2. Verify APK package identity
            val packageArchiveInfo = context.packageManager.getPackageArchiveInfo(
                targetFile.absolutePath,
                PackageManager.GET_ACTIVITIES
            )

            if (packageArchiveInfo == null) {
                targetFile.delete()
                return@withContext DownloadAndVerifyResult.VerificationFailed("Downloaded file is not a valid Android APK")
            }

            val expectedPackageName = context.packageName.removeSuffix(".debug")
            val archivePackage = packageArchiveInfo.packageName
            if (!archivePackage.startsWith(expectedPackageName)) {
                targetFile.delete()
                return@withContext DownloadAndVerifyResult.VerificationFailed(
                    "Downloaded APK package '$archivePackage' does not match installed app '$expectedPackageName'"
                )
            }

            DownloadAndVerifyResult.Success(targetFile, computedSha256)
        } catch (e: Exception) {
            DownloadAndVerifyResult.Failure("Download error: ${e.message}")
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun createManageUnknownAppSourcesIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun triggerPackageInstall(apkFile: File): InstallTriggerResult {
        if (!apkFile.exists()) {
            return InstallTriggerResult.Failure("APK file does not exist")
        }

        if (!canRequestPackageInstalls()) {
            return InstallTriggerResult.PermissionRequired
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            InstallTriggerResult.Launched
        } catch (e: Exception) {
            InstallTriggerResult.Failure("Failed to launch package installer: ${e.message}")
        }
    }
}
