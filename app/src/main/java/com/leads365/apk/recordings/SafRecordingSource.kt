package com.leads365.apk.recordings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.RecordingDiscoveryMethod
import com.leads365.apk.core.TimeHelpers

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class SafFolderInspection(
    val isAccessible: Boolean,
    val totalFilesCount: Int,
    val audioFilesCount: Int,
    val subfoldersCount: Int,
    val latestAudioFileName: String?,
    val latestAudioModifiedMs: Long?,
    val messageEn: String,
    val isLikelyWrongFolder: Boolean = false
) {
    val message: String get() = messageEn
}

open class SafRecordingSource(
    private val context: Context,
    private val getTreeUriString: () -> String?,
    private val hasPersistedPermission: (String) -> Boolean = { uriStr ->
        try {
            val targetUri = Uri.parse(uriStr)
            val targetDecoded = Uri.decode(targetUri.toString()).trimEnd('/')
            val resolver = context.contentResolver
            if (resolver != null) {
                resolver.persistedUriPermissions.any {
                    val permDecoded = Uri.decode(it.uri.toString()).trimEnd('/')
                    (it.uri == targetUri || it.uri.toString() == targetUri.toString() || permDecoded == targetDecoded) && it.isReadPermission
                }
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
) : RecordingSource {

    private val _folderUpdateEvents = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 5).apply {
        tryEmit(Unit)
    }
    val folderUpdateEvents: SharedFlow<Unit> = _folderUpdateEvents.asSharedFlow()

    fun notifyFolderUpdated() {
        _folderUpdateEvents.tryEmit(Unit)
    }

    constructor(context: Context, getTreeUri: () -> Uri?) : this(
        context = context,
        getTreeUriString = { getTreeUri()?.toString() }
    )

    open val treeUri: Uri?
        get() {
            val configured = getTreeUriString()?.let {
                try { Uri.parse(it) } catch (e: Exception) { null }
            }
            if (configured != null) return configured
            // Fallback to active persisted URI permission from Android system
            return try {
                context.contentResolver?.persistedUriPermissions
                    ?.firstOrNull { it.isReadPermission }?.uri
            } catch (e: Exception) {
                null
            }
        }

    override val discoveryMethod: RecordingDiscoveryMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK

    override fun isConfigured(): Boolean {
        if (!getTreeUriString().isNullOrBlank()) return true
        return try {
            context.contentResolver?.persistedUriPermissions?.any { it.isReadPermission } == true
        } catch (e: Exception) {
            false
        }
    }

    override fun isAccessible(): Boolean {
        val uriStr = getTreeUriString()
        if (!uriStr.isNullOrBlank()) {
            return hasPersistedPermission(uriStr)
        }
        return try {
            context.contentResolver?.persistedUriPermissions?.any { it.isReadPermission } == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Inspects the selected folder structure and counts audio files.
     * Recursively traverses subdirectories up to MAX_SEARCH_DEPTH (e.g. Call, Record/Call).
     * Provides clear diagnostics when the folder is empty or dialer recording is inactive.
     */
    open fun inspectFolderContent(): SafFolderInspection {
        val targetUri = treeUri ?: return SafFolderInspection(
            isAccessible = false,
            totalFilesCount = 0,
            audioFilesCount = 0,
            subfoldersCount = 0,
            latestAudioFileName = null,
            latestAudioModifiedMs = null,
            messageEn = "No folder selected. Please select your phone's call recording folder."
        )

        if (!isAccessible()) {
            return SafFolderInspection(
                isAccessible = false,
                totalFilesCount = 0,
                audioFilesCount = 0,
                subfoldersCount = 0,
                latestAudioFileName = null,
                latestAudioModifiedMs = null,
                messageEn = "Folder access permission was revoked. Please re-select the folder."
            )
        }

        val rootDoc = try {
            DocumentFile.fromTreeUri(context, targetUri)
        } catch (e: Exception) {
            null
        }

        if (rootDoc == null || !rootDoc.isDirectory || !rootDoc.canRead()) {
            return SafFolderInspection(
                isAccessible = false,
                totalFilesCount = 0,
                audioFilesCount = 0,
                subfoldersCount = 0,
                latestAudioFileName = null,
                latestAudioModifiedMs = null,
                messageEn = "Cannot read selected folder. The folder might have been deleted, moved, or access was restricted."
            )
        }

        var totalFiles = 0
        var audioFiles = 0
        var subfolders = 0
        var latestName: String? = null
        var latestMod: Long = 0L

        fun scanDirectory(dir: DocumentFile, depth: Int) {
            if (depth > MAX_SEARCH_DEPTH) return
            try {
                val entries = dir.listFiles()
                for (entry in entries) {
                    if (entry.isDirectory && entry.canRead()) {
                        subfolders++
                        scanDirectory(entry, depth + 1)
                    } else if (entry.isFile) {
                        totalFiles++
                        if (isAudioFile(entry)) {
                            audioFiles++
                            var mod = entry.lastModified()
                            if (mod in 1L..99_999_999_999L) {
                                mod *= 1000L
                            }
                            if (mod > latestMod) {
                                latestMod = mod
                                latestName = entry.name
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore subfolder read issues
            }
        }

        scanDirectory(rootDoc, depth = 1)

        val folderName = rootDoc.name ?: "Folder"
        val uriPath = targetUri.toString().lowercase()
        val folderLower = folderName.lowercase()
        val isSuspectCustomFolder = folderLower.contains("app recording") ||
                folderLower.contains("leads") ||
                folderLower.contains("download") ||
                folderLower.contains("document") ||
                (!folderLower.contains("record") && !folderLower.contains("call") && !folderLower.contains("voice") && !folderLower.contains("sound") && !uriPath.contains("record") && !uriPath.contains("call"))

        return when {
            audioFiles > 0 -> {
                SafFolderInspection(
                    isAccessible = true,
                    totalFilesCount = totalFiles,
                    audioFilesCount = audioFiles,
                    subfoldersCount = subfolders,
                    latestAudioFileName = latestName,
                    latestAudioModifiedMs = if (latestMod > 0) latestMod else null,
                    messageEn = "Found $audioFiles audio recording(s). Ready for call matching.",
                    isLikelyWrongFolder = false
                )
            }
            totalFiles == 0 && subfolders == 0 -> {
                val msg = if (isSuspectCustomFolder) {
                    "Selected folder '$folderName' is empty. Native phone dialers (Vivo, Samsung, Xiaomi) save to 'Recordings/Call' or 'Record/Call', NOT custom folders. Tap 'Change Folder' to select your phone's native recording folder."
                } else {
                    "Folder is empty. Please enable call recording in your phone dialer settings and make a call."
                }
                SafFolderInspection(
                    isAccessible = true,
                    totalFilesCount = 0,
                    audioFilesCount = 0,
                    subfoldersCount = 0,
                    latestAudioFileName = null,
                    latestAudioModifiedMs = null,
                    messageEn = msg,
                    isLikelyWrongFolder = isSuspectCustomFolder
                )
            }
            else -> {
                val msg = if (isSuspectCustomFolder) {
                    "Folder '$folderName' contains no audio recordings. Phone dialers save to 'Recordings/Call'. Please re-select the native phone recordings folder."
                } else {
                    "Folder contains $totalFiles file(s) across $subfolders subfolder(s), but no audio recordings (.m4a, .mp3, .wav). Ensure dialer auto-recording is turned on."
                }
                SafFolderInspection(
                    isAccessible = true,
                    totalFilesCount = totalFiles,
                    audioFilesCount = 0,
                    subfoldersCount = subfolders,
                    latestAudioFileName = null,
                    latestAudioModifiedMs = null,
                    messageEn = msg,
                    isLikelyWrongFolder = isSuspectCustomFolder
                )
            }
        }
    }

    override fun queryCandidates(
        callStartEpochMs: Long,
        callEndEpochMs: Long,
        callDurationSec: Long,
        normalizedNumber: String
    ): List<RecordingCandidate> {
        val targetUri = treeUri ?: return emptyList()
        if (!isAccessible()) return emptyList()

        val rootDoc = try {
            DocumentFile.fromTreeUri(context, targetUri)
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        if (!rootDoc.isDirectory || !rootDoc.canRead()) return emptyList()

        // Bounded time window: callStart - 120s (dialing/ringing allowance) to callEnd + 600s (10 minutes)
        val (windowStart, windowEnd) = TimeHelpers.calculateWindow(
            callStartMs = callStartEpochMs,
            callEndMs = callEndEpochMs,
            bufferBeforeSec = 120,
            bufferAfterSec = 600
        )

        android.util.Log.d("SafRecordingSource", "Querying SAF candidates for call. Window: $windowStart..$windowEnd (span ${(windowEnd - windowStart)/1000}s)")

        val candidates = mutableListOf<RecordingCandidate>()

        fun processFile(file: DocumentFile) {
            if (file.isFile && isAudioFile(file)) {
                var lastModified = file.lastModified()
                // Normalize timestamps from seconds to milliseconds if reported in seconds
                if (lastModified in 1L..99_999_999_999L) {
                    lastModified *= 1000L
                }

                // Accept if file modification is within the bounded window, or if lastModified is 0
                // (some SAF providers omit lastModified; in that case include for matcher scoring)
                if (lastModified == 0L || lastModified in windowStart..windowEnd) {
                    var fileLen = file.length()
                    val canRead = file.canRead()
                    var isReadable = false
                    var unreadableReason: String? = null

                    // Actively verify that the document stream can be opened
                    try {
                        context.contentResolver?.openFileDescriptor(file.uri, "r")?.use { pfd ->
                            val statSize = pfd.statSize
                            if (statSize > 0) {
                                fileLen = statSize
                                isReadable = true
                            } else if (fileLen > 0) {
                                isReadable = true
                            } else {
                                unreadableReason = "File size reported as 0 bytes"
                            }
                        } ?: run {
                            context.contentResolver?.openInputStream(file.uri)?.use { stream ->
                                val buf = ByteArray(64)
                                val read = stream.read(buf)
                                if (read > 0 || fileLen > 0) {
                                    isReadable = true
                                } else {
                                    unreadableReason = "Empty recording stream (0 bytes)"
                                }
                            } ?: run {
                                if (canRead && fileLen > 0) {
                                    isReadable = true
                                } else {
                                    unreadableReason = "Cannot open document stream"
                                }
                            }
                        }
                    } catch (e: SecurityException) {
                        unreadableReason = "Permission denied reading document file: ${e.message}"
                    } catch (e: Exception) {
                        if (canRead && fileLen > 0) {
                            isReadable = true
                        } else {
                            unreadableReason = "Cannot access audio stream: ${e.message}"
                        }
                    }

                    android.util.Log.d("SafRecordingSource", "SAF Candidate accepted: ${file.name}, size=$fileLen, readable=$isReadable, lastMod=$lastModified")
                    candidates.add(
                        RecordingCandidate(
                            uriString = file.uri.toString(),
                            displayName = file.name ?: "recording_${System.currentTimeMillis()}",
                            sizeBytes = fileLen,
                            lastModifiedEpochMs = if (lastModified > 0) lastModified else callEndEpochMs,
                            mimeType = file.type ?: "audio/m4a",
                            durationSeconds = null,
                            sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK,
                            isReadable = isReadable,
                            unreadableReason = unreadableReason
                        )
                    )
                }
            }
        }

        fun traverseDirectory(dir: DocumentFile, depth: Int) {
            if (depth > MAX_SEARCH_DEPTH) return
            try {
                val entries = dir.listFiles()
                for (entry in entries) {
                    if (entry.isFile) {
                        processFile(entry)
                    } else if (entry.isDirectory && entry.canRead()) {
                        traverseDirectory(entry, depth + 1)
                    }
                }
            } catch (e: SecurityException) {
                // Security exception when accessing specific child
            } catch (e: Exception) {
                // Non-fatal traversal error
            }
        }

        traverseDirectory(rootDoc, depth = 1)
        return candidates
    }

    open fun listDiscoveredRecordings(limit: Int = 100): List<RecordingCandidate> {
        val targetUri = treeUri ?: return emptyList()
        if (!isAccessible()) return emptyList()

        val rootDoc = try {
            DocumentFile.fromTreeUri(context, targetUri)
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        if (!rootDoc.isDirectory || !rootDoc.canRead()) return emptyList()

        val candidates = mutableListOf<RecordingCandidate>()

        fun processFile(file: DocumentFile) {
            if (candidates.size >= limit) return
            if (file.isFile && isAudioFile(file)) {
                var lastModified = file.lastModified()
                if (lastModified in 1L..99_999_999_999L) {
                    lastModified *= 1000L
                }
                var fileLen = file.length()
                val canRead = file.canRead()
                var isReadable = false
                var unreadableReason: String? = null

                try {
                    context.contentResolver?.openFileDescriptor(file.uri, "r")?.use { pfd ->
                        val statSize = pfd.statSize
                        if (statSize > 0) {
                            fileLen = statSize
                            isReadable = true
                        } else if (fileLen > 0) {
                            isReadable = true
                        } else {
                            unreadableReason = "File size reported as 0 bytes"
                        }
                    } ?: run {
                        context.contentResolver?.openInputStream(file.uri)?.use { stream ->
                            val buf = ByteArray(64)
                            val read = stream.read(buf)
                            if (read > 0 || fileLen > 0) {
                                isReadable = true
                            } else {
                                unreadableReason = "Empty recording stream (0 bytes)"
                            }
                        } ?: run {
                            if (canRead && fileLen > 0) {
                                isReadable = true
                            } else {
                                unreadableReason = "Cannot open document stream"
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    unreadableReason = "Permission denied: ${e.message}"
                } catch (e: Exception) {
                    if (canRead && fileLen > 0) {
                        isReadable = true
                    } else {
                        unreadableReason = "Cannot access audio stream: ${e.message}"
                    }
                }

                candidates.add(
                    RecordingCandidate(
                        uriString = file.uri.toString(),
                        displayName = file.name ?: "recording_${System.currentTimeMillis()}",
                        sizeBytes = fileLen,
                        lastModifiedEpochMs = if (lastModified > 0) lastModified else System.currentTimeMillis(),
                        mimeType = file.type ?: "audio/m4a",
                        durationSeconds = null,
                        sourceMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK,
                        isReadable = isReadable,
                        unreadableReason = unreadableReason
                    )
                )
            }
        }

        fun traverseDirectory(dir: DocumentFile, depth: Int) {
            if (depth > MAX_SEARCH_DEPTH || candidates.size >= limit) return
            try {
                val entries = dir.listFiles()
                for (entry in entries) {
                    if (candidates.size >= limit) break
                    if (entry.isFile) {
                        processFile(entry)
                    } else if (entry.isDirectory && entry.canRead()) {
                        traverseDirectory(entry, depth + 1)
                    }
                }
            } catch (e: Exception) {
                // Non-fatal traversal error
            }
        }

        traverseDirectory(rootDoc, depth = 1)
        return candidates.sortedByDescending { it.lastModifiedEpochMs }
    }

    private fun isAudioFile(file: DocumentFile): Boolean {
        val type = file.type?.lowercase() ?: ""
        val name = (file.name ?: "").lowercase()
        return type.startsWith("audio/") ||
                name.endsWith(".m4a") ||
                name.endsWith(".mp3") ||
                name.endsWith(".aac") ||
                name.endsWith(".amr") ||
                name.endsWith(".wav") ||
                name.endsWith(".ogg") ||
                name.endsWith(".3gp") ||
                name.endsWith(".awb") ||
                name.endsWith(".flac") ||
                name.endsWith(".opus")
    }

    companion object {
        const val MAX_SEARCH_DEPTH = 4

        fun takePersistablePermissions(context: Context, uri: Uri) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: SecurityException) {
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (e2: Exception) {
                    android.util.Log.w("SafRecordingSource", "Failed to take read persistable permission for $uri: ${e2.message}")
                }
            } catch (e: Exception) {
                android.util.Log.w("SafRecordingSource", "Failed to take persistable permission for $uri: ${e.message}")
            }
        }
    }
}
