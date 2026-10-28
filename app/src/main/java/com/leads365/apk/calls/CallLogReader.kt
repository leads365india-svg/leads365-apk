package com.leads365.apk.calls

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.leads365.apk.core.CallDirection
import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.PhoneNumberNormalizer
import kotlin.math.abs

enum class CallLogDiagnosticResult {
    PERMISSION_GRANTED_QUERY_SUCCESS,
    PERMISSION_NOT_GRANTED,
    PERMISSION_RESTRICTED,
    QUERY_EMPTY,
    PROVIDER_UNAVAILABLE,
    QUERY_FAILED
}

enum class CallLogAccessStatus {
    ACCESSIBLE,
    PERMISSION_DENIED,
    RESTRICTED_BY_PLATFORM,
    PROVIDER_ERROR
}

class CallLogReader(
    private val context: Context,
    private val normalizer: PhoneNumberNormalizer = PhoneNumberNormalizer()
) {

    fun hasCallLogPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Dedicated diagnostic operation to independently verify:
     * 1. Manifest permission state (checkSelfPermission).
     * 2. Platform/AppOps operational restriction (PermissionChecker).
     * 3. Actual CallLog provider accessibility using a bounded, minimum-projection query.
     * 4. Empty call history distinction from provider failures.
     */
    fun diagnoseCallLogAccess(): CallLogDiagnosticResult {
        val permissionState = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CALL_LOG
        )
        if (permissionState != PackageManager.PERMISSION_GRANTED) {
            return CallLogDiagnosticResult.PERMISSION_NOT_GRANTED
        }

        // Check AppOps/Platform restriction (e.g. Android 12 / Vivo Funtouch OS special app access)
        val appOpCheck = PermissionChecker.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CALL_LOG
        )
        if (appOpCheck == PermissionChecker.PERMISSION_DENIED_APP_OP) {
            return CallLogDiagnosticResult.PERMISSION_RESTRICTED
        }

        return try {
            val projection = arrayOf(CallLog.Calls._ID)
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val queryArgs = Bundle().apply {
                        putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
                        putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(CallLog.Calls.DATE))
                        putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                    }
                    context.contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        projection,
                        queryArgs,
                        null
                    )
                } catch (e: Exception) {
                    // Fallback to standard query if queryArgs bundle not supported by OEM provider
                    context.contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        projection,
                        null,
                        null,
                        "${CallLog.Calls.DATE} DESC"
                    )
                }
            } else {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )
            }

            cursor?.use { c ->
                if (c.moveToFirst()) {
                    CallLogDiagnosticResult.PERMISSION_GRANTED_QUERY_SUCCESS
                } else {
                    // Legitimate empty call log: provider works and permission is granted!
                    CallLogDiagnosticResult.QUERY_EMPTY
                }
            } ?: CallLogDiagnosticResult.PROVIDER_UNAVAILABLE
        } catch (e: SecurityException) {
            CallLogDiagnosticResult.PERMISSION_RESTRICTED
        } catch (e: Exception) {
            CallLogDiagnosticResult.QUERY_FAILED
        }
    }

    /**
     * Probes the CallLog provider to verify whether Android actually permits queries,
     * distinguishing normal permission denial from OEM/AppOps platform restrictions.
     */
    fun checkCallLogAccess(): CallLogAccessStatus {
        return when (diagnoseCallLogAccess()) {
            CallLogDiagnosticResult.PERMISSION_GRANTED_QUERY_SUCCESS,
            CallLogDiagnosticResult.QUERY_EMPTY -> CallLogAccessStatus.ACCESSIBLE
            CallLogDiagnosticResult.PERMISSION_NOT_GRANTED -> CallLogAccessStatus.PERMISSION_DENIED
            CallLogDiagnosticResult.PERMISSION_RESTRICTED -> CallLogAccessStatus.RESTRICTED_BY_PLATFORM
            CallLogDiagnosticResult.PROVIDER_UNAVAILABLE,
            CallLogDiagnosticResult.QUERY_FAILED -> CallLogAccessStatus.PROVIDER_ERROR
        }
    }

    /**
     * Finds the most recent call whose completion time falls within [maxEndRecencyMs].
     *
     * Note: In Android CallLog, CallLog.Calls.DATE represents the call START time.
     * We query recent entries ordered by DATE DESC and evaluate recency against the
     * call's actual END time (DATE + DURATION * 1000) so that calls of any duration
     * (e.g. 2 min, 5 min) are never incorrectly filtered out.
     */
    fun findMostRecentCall(
        maxEndRecencyMs: Long = 900_000L, // 15 minutes recency window
        queryLimit: Int = 10
    ): CallEvent? {
        val calls = queryRecentCallEntries(limit = queryLimit)
        if (calls.isEmpty()) return null

        val now = System.currentTimeMillis()
        // Filter calls that ended recently (within maxEndRecencyMs)
        return calls.firstOrNull { call ->
            val timeSinceEnd = now - call.endTimeEpochMs
            // Accept calls that ended within the window (with 30s future tolerance for clock drift)
            timeSinceEnd in -30_000L..maxEndRecencyMs
        }
    }

    /**
     * Queries recent CallLog entries without tight duration filtering.
     */
    fun queryRecentCallEntries(limit: Int = 10): List<CallEvent> {
        if (!hasCallLogPermission()) {
            return emptyList()
        }

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
            CallLog.Calls.PHONE_ACCOUNT_ID
        )

        // Query entries started within the last 12 hours
        val minStartTime = System.currentTimeMillis() - 43_200_000L
        val selection = "${CallLog.Calls.DATE} >= ?"
        val selectionArgs = arrayOf(minStartTime.toString())
        val sortOrder = "${CallLog.Calls.DATE} DESC"

        val result = mutableListOf<CallEvent>()

        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(CallLog.Calls._ID)
                val numCol = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val dateCol = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durCol = cursor.getColumnIndex(CallLog.Calls.DURATION)
                val typeCol = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val simCol = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

                while (cursor.moveToNext() && result.size < limit) {
                    val id = if (idCol >= 0) cursor.getString(idCol) else "call_${System.currentTimeMillis()}"
                    val rawNum = if (numCol >= 0) cursor.getString(numCol) ?: "" else ""
                    val startTime = if (dateCol >= 0) cursor.getLong(dateCol) else System.currentTimeMillis()
                    val durationSec = if (durCol >= 0) cursor.getLong(durCol) else 0L
                    val callType = if (typeCol >= 0) cursor.getInt(typeCol) else CallLog.Calls.OUTGOING_TYPE
                    val simId = if (simCol >= 0) cursor.getString(simCol) else null

                    val direction = when (callType) {
                        CallLog.Calls.INCOMING_TYPE -> CallDirection.INCOMING
                        CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
                        CallLog.Calls.MISSED_TYPE -> CallDirection.MISSED
                        else -> CallDirection.UNKNOWN
                    }

                    val normalized = normalizer.normalizeToE164(rawNum) ?: rawNum
                    val endTime = startTime + (durationSec * 1000L)

                    result.add(
                        CallEvent(
                            id = id,
                            rawNumber = rawNum,
                            normalizedNumber = normalized,
                            startTimeEpochMs = startTime,
                            endTimeEpochMs = endTime,
                            durationSeconds = durationSec,
                            direction = direction,
                            simId = simId
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }

        return result
    }
}
