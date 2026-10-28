package com.leads365.apk.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TimeHelpers {

    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private val FILENAME_TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun formatEpochMs(epochMs: Long): String {
        if (epochMs <= 0) return "N/A"
        return DATE_FORMAT.format(Date(epochMs))
    }

    fun formatDurationSeconds(seconds: Long): String {
        if (seconds < 0) return "0s"
        val mins = seconds / 60
        val secs = seconds % 60
        return if (mins > 0) {
            String.format(Locale.US, "%dm %02ds", mins, secs)
        } else {
            "${secs}s"
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.US, "%.2f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    fun isWithinTolerance(actualSec: Long, expectedSec: Long, maxDeltaSec: Long = 10, maxDeltaPercent: Double = 0.20): Boolean {
        val diff = Math.abs(actualSec - expectedSec)
        if (diff <= maxDeltaSec) return true
        if (expectedSec > 0 && (diff.toDouble() / expectedSec) <= maxDeltaPercent) return true
        return false
    }

    fun calculateWindow(callStartMs: Long, callEndMs: Long, bufferBeforeSec: Long = 15, bufferAfterSec: Long = 180): Pair<Long, Long> {
        val windowStart = Math.max(0L, callStartMs - (bufferBeforeSec * 1000L))
        val windowEnd = callEndMs + (bufferAfterSec * 1000L)
        return Pair(windowStart, windowEnd)
    }
}
