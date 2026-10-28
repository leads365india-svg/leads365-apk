package com.leads365.apk.recordings

import android.content.Context
import android.net.Uri
import com.leads365.apk.core.PrivacyRedactor
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.UploadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StreamingTestUploader(
    private val context: Context,
    private val client: OkHttpClient = defaultClient(),
    private val openStream: (String) -> InputStream? = { uriStr ->
        try {
            context.contentResolver.openInputStream(Uri.parse(uriStr))
        } catch (e: Exception) {
            null
        }
    }
) {

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    private val isUploadingInProgress = AtomicBoolean(false)
    private val completedUploadChecksums = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun resetState() {
        if (!isUploadingInProgress.get()) {
            _uploadState.value = UploadState.Idle
        }
    }

    fun clearCompletedUploads() {
        completedUploadChecksums.clear()
    }

    /**
     * Streams the recording file directly to the test endpoint without buffering the entire file into memory.
     * Computes SHA-256 and provides live byte progress updates.
     */
    suspend fun uploadTestRecording(
        candidate: RecordingCandidate,
        endpointUrl: String?,
        customAuthToken: String? = null
    ): UploadState = withContext(Dispatchers.IO) {
        val contentUriString = candidate.uriString

        // 1. Inspect stream to confirm actual readability and compute SHA-256
        val inspection = inspectStream(contentUriString)
        if (inspection == null || (inspection.bytesRead <= 0 && candidate.sizeBytes <= 0)) {
            val failed = UploadState.Failed(
                "Recording file cannot be opened or read (${candidate.unreadableReason ?: "inaccessible or 0 bytes"}).",
                isRetryable = false
            )
            _uploadState.value = failed
            return@withContext failed
        }

        if (endpointUrl.isNullOrBlank()) {
            val localInfo = UploadState.Failed(
                "Recording matched locally. Server upload is unavailable in Local Recording Test Mode; sign in to upload.",
                isRetryable = false
            )
            _uploadState.value = localInfo
            return@withContext localInfo
        }

        // In-flight concurrency lock: prevent duplicate submission
        if (!isUploadingInProgress.compareAndSet(false, true)) {
            val failed = UploadState.Failed("An upload is already in progress. Duplicate submission prevented.", isRetryable = true)
            return@withContext failed
        }

        try {
            val checksumSha256 = inspection.sha256Hex

            // Check if this recording was already successfully uploaded to prevent duplicate submission on retries
            if (completedUploadChecksums.contains(checksumSha256)) {
                val alreadyUploaded = UploadState.Success(
                    httpStatusCode = 200,
                    responseMessage = "Recording with SHA-256 ${checksumSha256.take(8)}... was already successfully uploaded. Duplicate upload prevented.",
                    timestampEpochMs = System.currentTimeMillis()
                )
                _uploadState.value = alreadyUploaded
                return@withContext alreadyUploaded
            }
            val mime = candidate.mimeType.ifBlank { "audio/m4a" }
            val mediaType = mime.toMediaTypeOrNull()

            // Ensure exact content length from stream inspection
            val totalBytes = if (inspection.bytesRead > 0) inspection.bytesRead else candidate.sizeBytes

            // 2. Build Streaming RequestBody
            val streamingBody = object : RequestBody() {
                override fun contentType(): MediaType? = mediaType
                override fun contentLength(): Long = totalBytes

                override fun writeTo(sink: BufferedSink) {
                    val inputStream = openStream(contentUriString)
                        ?: throw IllegalStateException("Cannot open audio stream from URI: $contentUriString")

                    inputStream.use { stream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytesRead: Int
                        var uploadedSoFar = 0L

                        while (stream.read(buffer).also { bytesRead = it } != -1) {
                            sink.write(buffer, 0, bytesRead)
                            uploadedSoFar += bytesRead
                            val progress = if (totalBytes > 0) ((uploadedSoFar * 100) / totalBytes).toInt() else 0
                            _uploadState.value = UploadState.Uploading(progress, uploadedSoFar, totalBytes)
                        }
                    }
                }
            }

            // 3. Build HTTP Request
            val requestBuilder = Request.Builder()
                .url(endpointUrl)
                .post(streamingBody)
                .addHeader("Content-Type", mime)
                .addHeader("X-Content-SHA256", checksumSha256)
                .addHeader("X-Audio-SHA256", checksumSha256)
                .addHeader("X-File-Name", PrivacyRedactor.redactFilename(candidate.displayName))
                .addHeader("X-Discovery-Method", candidate.sourceMethod.name)

            if (!customAuthToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $customAuthToken")
            }

            val request = requestBuilder.build()

            // 4. Execute streaming request
            client.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    completedUploadChecksums.add(checksumSha256)
                    val success = UploadState.Success(
                        httpStatusCode = code,
                        responseMessage = "Uploaded successfully (HTTP $code). SHA-256: ${checksumSha256.take(8)}...",
                        timestampEpochMs = System.currentTimeMillis()
                    )
                    _uploadState.value = success
                    success
                } else {
                    val isRetryable = code >= 500 || code == 429
                    reportFailure("Server responded with HTTP $code: ${response.message}", isRetryable = isRetryable)
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            reportFailure("Upload timed out connecting to test endpoint.", isRetryable = true)
        } catch (e: java.io.IOException) {
            reportFailure("Network I/O error during streaming upload: ${e.message}", isRetryable = true)
        } catch (e: Exception) {
            reportFailure("Upload error: ${e.message}", isRetryable = false)
        } finally {
            isUploadingInProgress.set(false)
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
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
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
            null
        }
    }

    fun calculateSha256(uriString: String): String? = inspectStream(uriString)?.sha256Hex

    fun calculateSha256(uri: Uri): String? = calculateSha256(uri.toString())

    private fun reportFailure(msg: String, isRetryable: Boolean): UploadState.Failed {
        val failed = UploadState.Failed(msg, isRetryable)
        _uploadState.value = failed
        return failed
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
