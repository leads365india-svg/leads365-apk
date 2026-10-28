package com.leads365.apk.recordings

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.RecordingDiscoveryMethod
import com.leads365.apk.core.TimeHelpers

open class MediaStoreRecordingSource(
    private val context: Context
) : RecordingSource {

    override val discoveryMethod: RecordingDiscoveryMethod = RecordingDiscoveryMethod.MEDIA_STORE

    override fun isConfigured(): Boolean = true

    override fun isAccessible(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun queryCandidates(
        callStartEpochMs: Long,
        callEndEpochMs: Long,
        callDurationSec: Long,
        normalizedNumber: String
    ): List<RecordingCandidate> {
        if (!isAccessible()) return emptyList()

        // Bounded time window: callStart - 90s to callEnd + 300s (5 minutes)
        val (windowStartMs, windowEndMs) = TimeHelpers.calculateWindow(
            callStartMs = callStartEpochMs,
            callEndMs = callEndEpochMs,
            bufferBeforeSec = 90,
            bufferAfterSec = 300
        )

        val windowStartSec = windowStartMs / 1000L
        val windowEndSec = windowEndMs / 1000L

        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Audio.Media.RELATIVE_PATH)
        }

        // Bounded selection on DATE_MODIFIED or DATE_ADDED
        val selection = "(${MediaStore.Audio.Media.DATE_MODIFIED} >= ? AND ${MediaStore.Audio.Media.DATE_MODIFIED} <= ?) OR (${MediaStore.Audio.Media.DATE_ADDED} >= ? AND ${MediaStore.Audio.Media.DATE_ADDED} <= ?)"
        val selectionArgs = arrayOf(
            windowStartSec.toString(),
            windowEndSec.toString(),
            windowStartSec.toString(),
            windowEndSec.toString()
        )
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        val candidates = mutableListOf<RecordingCandidate>()

        try {
            android.util.Log.d("MediaStoreRecordingSource", "Querying MediaStore between $windowStartSec and $windowEndSec")
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val addedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    val name = cursor.getString(nameCol) ?: "recording_$id"
                    val size = cursor.getLong(sizeCol)
                    val dateSec = cursor.getLong(dateCol)
                    val addedSec = if (addedCol >= 0) cursor.getLong(addedCol) else 0L
                    val effectiveDateSec = if (dateSec > 0) dateSec else addedSec
                    val durMs = cursor.getLong(durCol)
                    val mime = cursor.getString(mimeCol) ?: "audio/m4a"

                    val durSec = if (durMs > 0) durMs / 1000L else null

                    val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    } else {
                        cursor.getColumnIndex("_data")
                    }
                    val rawPath = if (pathCol >= 0) cursor.getString(pathCol) else null

                    // Readability and accessibility verification
                    var isReadable = false
                    var unreadableReason: String? = null
                    var verifiedSize = size

                    if (size <= 0) {
                        unreadableReason = "File size reported as 0 bytes"
                    } else {
                        try {
                            context.contentResolver.openFileDescriptor(contentUri, "r")?.use { pfd ->
                                val statSize = pfd.statSize
                                if (statSize > 0) {
                                    verifiedSize = statSize
                                    isReadable = true
                                } else {
                                    unreadableReason = "File descriptor size is 0 bytes"
                                }
                            } ?: run {
                                unreadableReason = "Content resolver returned null file descriptor"
                            }
                        } catch (e: SecurityException) {
                            unreadableReason = "Permission denied opening audio stream: ${e.message}"
                        } catch (e: java.io.FileNotFoundException) {
                            unreadableReason = "Audio file not found on storage (deleted or moved)"
                        } catch (e: Exception) {
                            unreadableReason = "Cannot access audio stream: ${e.message}"
                        }

                        if (isReadable) {
                            try {
                                context.contentResolver.openInputStream(contentUri)?.use { stream ->
                                    val buf = ByteArray(128)
                                    val read = stream.read(buf)
                                    if (read <= 0 && verifiedSize <= 0) {
                                        isReadable = false
                                        unreadableReason = "Audio stream returned 0 bytes"
                                    }
                                } ?: run {
                                    isReadable = false
                                    unreadableReason = "Content resolver returned null input stream"
                                }
                            } catch (e: Exception) {
                                isReadable = false
                                unreadableReason = "Stream read error: ${e.message}"
                            }
                        }
                    }

                    android.util.Log.d("MediaStoreRecordingSource", "Found candidate: $name, size=$verifiedSize, readable=$isReadable, path=$rawPath")

                    candidates.add(
                        RecordingCandidate(
                            uriString = contentUri.toString(),
                            displayName = name,
                            sizeBytes = verifiedSize,
                            lastModifiedEpochMs = if (effectiveDateSec > 100_000_000_000L) effectiveDateSec else effectiveDateSec * 1000L,
                            mimeType = mime,
                            durationSeconds = durSec,
                            sourceMethod = RecordingDiscoveryMethod.MEDIA_STORE,
                            relativePath = rawPath,
                            isReadable = isReadable,
                            unreadableReason = unreadableReason
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }

        return candidates
    }

    fun listDiscoveredRecordings(limit: Int = 100): List<RecordingCandidate> {
        if (!isAccessible()) return emptyList()

        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Audio.Media.RELATIVE_PATH)
        }

        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC LIMIT $limit"
        val candidates = mutableListOf<RecordingCandidate>()

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)

                while (cursor.moveToNext() && candidates.size < limit) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    val name = cursor.getString(nameCol) ?: "recording_$id"
                    val size = cursor.getLong(sizeCol)
                    val dateSec = cursor.getLong(dateCol)
                    val durMs = cursor.getLong(durCol)
                    val mime = cursor.getString(mimeCol) ?: "audio/m4a"

                    candidates.add(
                        RecordingCandidate(
                            uriString = contentUri.toString(),
                            displayName = name,
                            sizeBytes = size,
                            lastModifiedEpochMs = if (dateSec > 100_000_000_000L) dateSec else dateSec * 1000L,
                            mimeType = mime,
                            durationSeconds = if (durMs > 0) durMs / 1000L else null,
                            sourceMethod = RecordingDiscoveryMethod.MEDIA_STORE,
                            isReadable = true,
                            unreadableReason = null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Ignore MediaStore query errors
        }
        return candidates
    }
}
