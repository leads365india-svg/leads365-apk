package com.leads365.apk

import com.leads365.apk.calls.CallLogAccessStatus
import com.leads365.apk.calls.CallLogDiagnosticResult
import com.leads365.apk.core.PermissionItem
import com.leads365.apk.core.PermissionStatus
import com.leads365.apk.diagnostics.DiagnosticsManager
import com.leads365.apk.ui.MainUiState
import org.junit.Assert.*
import org.junit.Test

class CallLogDiagnosticAndPermissionTest {

    @Test
    fun testCallLogDiagnosticResultMappingInDiagnosticsManager() {
        val mockContext = object : android.content.ContextWrapper(null) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
                return android.content.pm.PackageManager.PERMISSION_DENIED
            }
        }

        // 1. QUERY_EMPTY must map to PermissionStatus.GRANTED (Not Denied!)
        val diagEmpty = DiagnosticsManager(
            context = mockContext,
            isSafConfigured = { true },
            isSafValid = { true },
            isMediaStoreAccessible = { true },
            diagnoseCallLog = { CallLogDiagnosticResult.QUERY_EMPTY }
        )
        val permsEmpty = diagEmpty.checkPermissions()
        val callLogItemEmpty = permsEmpty.first { it.id == "perm_call_log" }
        assertEquals(
            "An empty call log with valid permission must be GRANTED, not DENIED",
            PermissionStatus.GRANTED,
            callLogItemEmpty.status
        )
        assertTrue(callLogItemEmpty.rationale.contains("history is empty"))

        // 2. PERMISSION_GRANTED_QUERY_SUCCESS must map to GRANTED
        val diagSuccess = DiagnosticsManager(
            context = mockContext,
            isSafConfigured = { true },
            isSafValid = { true },
            isMediaStoreAccessible = { true },
            diagnoseCallLog = { CallLogDiagnosticResult.PERMISSION_GRANTED_QUERY_SUCCESS }
        )
        val permsSuccess = diagSuccess.checkPermissions()
        val callLogItemSuccess = permsSuccess.first { it.id == "perm_call_log" }
        assertEquals(PermissionStatus.GRANTED, callLogItemSuccess.status)

        // 3. PERMISSION_NOT_GRANTED must map to NOT_GRANTED
        val diagNotGranted = DiagnosticsManager(
            context = mockContext,
            isSafConfigured = { true },
            isSafValid = { true },
            isMediaStoreAccessible = { true },
            diagnoseCallLog = { CallLogDiagnosticResult.PERMISSION_NOT_GRANTED }
        )
        val permsNotGranted = diagNotGranted.checkPermissions()
        val callLogItemNotGranted = permsNotGranted.first { it.id == "perm_call_log" }
        assertEquals(PermissionStatus.NOT_GRANTED, callLogItemNotGranted.status)

        // 4. PERMISSION_RESTRICTED (OEM / AppOps / SecurityException) must map to RESTRICTED
        val diagRestricted = DiagnosticsManager(
            context = mockContext,
            isSafConfigured = { true },
            isSafValid = { true },
            isMediaStoreAccessible = { true },
            diagnoseCallLog = { CallLogDiagnosticResult.PERMISSION_RESTRICTED }
        )
        val permsRestricted = diagRestricted.checkPermissions()
        val callLogItemRestricted = permsRestricted.first { it.id == "perm_call_log" }
        assertEquals(PermissionStatus.RESTRICTED, callLogItemRestricted.status)
        assertTrue(callLogItemRestricted.rationale.contains("restricted by Android system or Vivo privacy settings"))

        // 5. PROVIDER_UNAVAILABLE must map to UNAVAILABLE
        val diagUnavailable = DiagnosticsManager(
            context = mockContext,
            isSafConfigured = { true },
            isSafValid = { true },
            isMediaStoreAccessible = { true },
            diagnoseCallLog = { CallLogDiagnosticResult.PROVIDER_UNAVAILABLE }
        )
        val permsUnavailable = diagUnavailable.checkPermissions()
        val callLogItemUnavailable = permsUnavailable.first { it.id == "perm_call_log" }
        assertEquals(PermissionStatus.UNAVAILABLE, callLogItemUnavailable.status)

        // 6. QUERY_FAILED must map to ERROR
        val diagFailed = DiagnosticsManager(
            context = mockContext,
            isSafConfigured = { true },
            isSafValid = { true },
            isMediaStoreAccessible = { true },
            diagnoseCallLog = { CallLogDiagnosticResult.QUERY_FAILED }
        )
        val permsFailed = diagFailed.checkPermissions()
        val callLogItemFailed = permsFailed.first { it.id == "perm_call_log" }
        assertEquals(PermissionStatus.ERROR, callLogItemFailed.status)
    }

    @Test
    fun testEmptyCallLogAllowsProgressionToStep2() {
        val permissions = listOf(
            PermissionItem("perm_phone_state", "Phone State", "detect call", PermissionStatus.GRANTED, isMandatory = true),
            PermissionItem("perm_call_log", "Call Log", "read recent", PermissionStatus.GRANTED, isMandatory = true)
        )
        val state = MainUiState(permissions = permissions)
        assertTrue(
            "User must be allowed to continue to Step 2 when Call Log is granted even if empty",
            state.canContinueToStep2
        )
    }

    @Test
    fun testRestrictedOrNotGrantedCallLogBlocksStep2() {
        val restrictedPermissions = listOf(
            PermissionItem("perm_phone_state", "Phone State", "detect call", PermissionStatus.GRANTED, isMandatory = true),
            PermissionItem("perm_call_log", "Call Log", "read recent", PermissionStatus.RESTRICTED, isMandatory = true)
        )
        val restrictedState = MainUiState(permissions = restrictedPermissions)
        assertFalse(
            "Step 2 progression must be blocked when Call Log is RESTRICTED",
            restrictedState.canContinueToStep2
        )

        val notGrantedPermissions = listOf(
            PermissionItem("perm_phone_state", "Phone State", "detect call", PermissionStatus.GRANTED, isMandatory = true),
            PermissionItem("perm_call_log", "Call Log", "read recent", PermissionStatus.NOT_GRANTED, isMandatory = true)
        )
        val notGrantedState = MainUiState(permissions = notGrantedPermissions)
        assertFalse(
            "Step 2 progression must be blocked when Call Log is NOT_GRANTED",
            notGrantedState.canContinueToStep2
        )
    }

    @Test
    fun testPermissionStateLifecycleTransition() {
        // Step 1: Initial state (Not Granted)
        var state = MainUiState(
            permissions = listOf(
                PermissionItem("perm_phone_state", "Phone State", "", PermissionStatus.NOT_GRANTED, isMandatory = true),
                PermissionItem("perm_call_log", "Call Log", "", PermissionStatus.NOT_GRANTED, isMandatory = true)
            )
        )
        assertFalse("Initial state must not allow continue", state.canContinueToStep2)

        // Step 2: User grants permission in runtime dialog or Settings
        state = state.copy(
            permissions = listOf(
                PermissionItem("perm_phone_state", "Phone State", "", PermissionStatus.GRANTED, isMandatory = true),
                PermissionItem("perm_call_log", "Call Log", "", PermissionStatus.GRANTED, isMandatory = true)
            )
        )
        assertTrue("Granted state must allow continue", state.canContinueToStep2)

        // Step 3: User backgrounds app and revokes permission in Android Settings
        state = state.copy(
            permissions = listOf(
                PermissionItem("perm_phone_state", "Phone State", "", PermissionStatus.NOT_GRANTED, isMandatory = true),
                PermissionItem("perm_call_log", "Call Log", "", PermissionStatus.NOT_GRANTED, isMandatory = true)
            )
        )
        assertFalse("Revoked permission upon onResume must immediately block Step 2", state.canContinueToStep2)
    }
}
