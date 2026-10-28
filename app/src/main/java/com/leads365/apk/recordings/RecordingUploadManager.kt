package com.leads365.apk.recordings

import android.content.Context
import android.net.Uri
import android.util.Log
import com.leads365.apk.core.PrivacyRedactor
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.network.auth.TokenProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

sealed class UploadExecutionResult {
    data class Success(
        val callId: String,
        val recordingUrl: String?,
        val checksum: String,
        val isAlreadyUploaded: Boolean = false
    ) : UploadExecutionResult()

    data class RetryableFailure(
        val statusCode: Int?,
        val reason: String
    ) : UploadExecutionResult()

    data class PermanentFailure(
        val statusCode: Int?,
        val reason: String
    ) : UploadExecutionResult()

    data class RejectedByServer(
        val statusCode: Int,
        val reason: String
    ) : UploadExecutionResult()
}

@Singleton
open class RecordingUploadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val tokenProvider: TokenProvider,
    @Named("baseUrl") private val baseUrl: String
) {

    open fun openStream(uriString: String): InputStream? {
        return try {
            context.contentResolver.openInputStream(Uri.parse(uriString))
        } catch (e: Exception) {
            null
        }
    }

    data class StreamInspection(
        val sha256Hex: String,
        val bytesRead: Long
    )

    fun inspectStream(uriString: String): StreamInspection? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val stream: InputStream = openStream(uriString) ?: return null
            var totalBytes = 0L
            stream.use {
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (it.read(buffer).also { bytesRead = it } != -1) {
                    md.update(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                }
            }
            val digest = md.digest()
            val hex = digest.joinToString("") { "%02x".format(it) }
            StreamInspection(hex, totalBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating checksum for $uriString: ${e.message}")
            null
        }
    }

    suspend fun uploadRecording(
        task: UploadTaskEntity,
        onProgress: ((progressPercent: Int, bytesSent: Long, totalBytes: Long) -> Unit)? = null
    ): UploadExecutionResult = withContext(Dispatchers.IO) {
        val callId = task.callId
        if (callId.isNullOrBlank()) {
            return@withContext UploadExecutionResult.PermanentFailure(null, "Missing callId on upload task")
        }

        // 1. Max File Size Enforcement (50MB upfront check)
        if (task.fileSizeBytes > MAX_FILE_SIZE_BYTES) {
            return@withContext UploadExecutionResult.PermanentFailure(
                413,
                "File size (${task.fileSizeBytes} bytes) exceeds maximum permitted 50MB limit"
            )
        }

        // 2. Inspect stream without loading entire file into memory
        val inspection = inspectStream(task.localFileUri)
            ?: return@withContext UploadExecutionResult.PermanentFailure(null, "Cannot read recording file: ${task.localFileUri}")

        val totalBytes = inspection.bytesRead
        val checksumSha256 = inspection.sha256Hex

        if (totalBytes > MAX_FILE_SIZE_BYTES) {
            return@withContext UploadExecutionResult.PermanentFailure(
                413,
                "File size ($totalBytes bytes) exceeds maximum permitted 50MB limit"
            )
        }

        // 3. MIME type detection & validation
        val resolvedMime = resolveMimeType(task.fileName)
        if (!ALLOWED_MIME_TYPES.contains(resolvedMime)) {
            return@withContext UploadExecutionResult.RejectedByServer(
                415,
                "Unsupported recording MIME type: $resolvedMime. Permitted audio formats: m4a, mp3, aac, amr, wav, 3gp."
            )
        }

        val mediaType = resolvedMime.toMediaTypeOrNull()

        // 4. Construct Streaming RequestBody
        val streamingBody = object : RequestBody() {
            override fun contentType(): MediaType? = mediaType
            override fun contentLength(): Long = totalBytes

            override fun writeTo(sink: BufferedSink) {
                val inputStream = openStream(task.localFileUri)
                    ?: throw IOException("Cannot open input stream for: ${task.localFileUri}")

                inputStream.use { stream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var uploadedBytes = 0L

                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        sink.write(buffer, 0, bytesRead)
                        uploadedBytes += bytesRead
                        val progress = if (totalBytes > 0) ((uploadedBytes * 100) / totalBytes).toInt() else 0
                        onProgress?.invoke(progress, uploadedBytes, totalBytes)
                    }
                }
            }
        }

        // 5. Construct URL
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val uploadUrl = if (cleanBaseUrl.endsWith("/api")) {
            "$cleanBaseUrl/calls/$callId/recording"
        } else {
            "$cleanBaseUrl/api/calls/$callId/recording"
        }

        val redactedName = PrivacyRedactor.redactFilename(task.fileName)
        val requestBuilder = Request.Builder()
            .url(uploadUrl)
            .put(streamingBody)
            .addHeader("Content-Type", resolvedMime)
            .addHeader("X-Content-SHA256", checksumSha256)
            .addHeader("X-Audio-SHA256", checksumSha256)
            .addHeader("X-File-Name", redactedName)

        val token = tokenProvider.getAccessToken()
        if (token.isNullOrBlank()) {
            Log.i(TAG, "Local Recording Test Mode: Server upload disabled because no authenticated session exists.")
            return@withContext UploadExecutionResult.PermanentFailure(
                401,
                "Local Recording Test Mode — server upload unavailable until login"
            )
        }
        requestBuilder.addHeader("Authorization", "Bearer $token")

        // 6. Execute Streaming Upload
        try {
            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string().orEmpty()

                when (code) {
                    200, 201 -> {
                        val isAlreadyUploaded = responseBody.contains("already uploaded", ignoreCase = true)
                        val recordingUrl = try {
                            val json = JSONObject(responseBody)
                            if (json.has("recordingUrl") && !json.isNull("recordingUrl")) json.getString("recordingUrl") else null
                        } catch (e: Exception) {
                            null
                        }
                        UploadExecutionResult.Success(
                            callId = callId,
                            recordingUrl = recordingUrl,
                            checksum = checksumSha256,
                            isAlreadyUploaded = isAlreadyUploaded
                        )
                    }
                    400 -> {
                        UploadExecutionResult.RejectedByServer(400, "Server rejected upload (checksum or bad request): $responseBody")
                    }
                    401 -> {
                        UploadExecutionResult.RetryableFailure(401, "Authentication required or revoked device token")
                    }
                    403 -> {
                        UploadExecutionResult.RejectedByServer(403, "Server rejected upload (DNR, cross-tenant or unassigned lead): $responseBody")
                    }
                    413 -> {
                        UploadExecutionResult.PermanentFailure(413, "File exceeds maximum permitted size (50MB)")
                    }
                    415 -> {
                        UploadExecutionResult.RejectedByServer(415, "Server rejected media type: $resolvedMime")
                    }
                    429 -> {
                        UploadExecutionResult.RetryableFailure(429, "Rate limit hit (HTTP 429). Will retry with exponential backoff.")
                    }
                    in 500..599 -> {
                        UploadExecutionResult.RetryableFailure(code, "Server error (HTTP $code). Will retry.")
                    }
                    else -> {
                        UploadExecutionResult.PermanentFailure(code, "Unexpected response from upload server: HTTP $code - $responseBody")
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            UploadExecutionResult.RetryableFailure(null, "Connection timed out during streaming upload: ${e.message}")
        } catch (e: IOException) {
            UploadExecutionResult.RetryableFailure(null, "Network I/O failure during streaming upload: ${e.message}")
        } catch (e: Exception) {
            UploadExecutionResult.PermanentFailure(null, "Upload error: ${e.message}")
        }
    }

    fun resolveMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".m4a") -> "audio/m4a"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".aac") -> "audio/aac"
            lower.endsWith(".amr") -> "audio/amr"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".ogg") -> "audio/ogg"
            lower.endsWith(".3gp") || lower.endsWith(".3gpp") -> "audio/3gpp"
            else -> "audio/m4a"
        }
    }

    companion object {
        private const val TAG = "RecordingUploadManager"
        private const val BUFFER_SIZE = 8192
        const val MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024L // 50MB

        val ALLOWED_MIME_TYPES = setOf(
            "audio/m4a",
            "audio/mp4",
            "audio/aac",
            "audio/mpeg",
            "audio/mp3",
            "audio/amr",
            "audio/3gpp",
            "audio/wav",
            "audio/ogg",
            "audio/x-m4a"
        )
    }
}
