package com.leads365.apk.core

import org.junit.Assert.*
import org.junit.Test

class AppResultTest {

    @Test
    fun `AppResult Success maps and transforms correctly`() {
        val success: AppResult<Int> = AppResult.Success(42)

        assertTrue(success.isSuccess)
        assertFalse(success.isFailure)
        assertEquals(42, success.getOrNull())
        assertEquals(42, success.getOrDefault(0))

        val mapped = success.map { it * 2 }
        assertEquals(84, mapped.getOrNull())

        val flatMapped = success.flatMap { AppResult.Success("Value is $it") }
        assertEquals("Value is 42", flatMapped.getOrNull())
    }

    @Test
    fun `AppResult Failure preserves AppError and falls back to default`() {
        val error = AppError.NetworkError("No connection", httpCode = 503)
        val failure: AppResult<String> = AppResult.Failure(error)

        assertFalse(failure.isSuccess)
        assertTrue(failure.isFailure)
        assertNull(failure.getOrNull())
        assertEquals("fallback", failure.getOrDefault("fallback"))

        val mapped = failure.map { it.uppercase() }
        assertTrue(mapped.isFailure)

        var errorCaptured: AppError? = null
        failure.onFailure { errorCaptured = it }
        assertEquals(error, errorCaptured)
    }

    @Test
    fun `AppError subclasses preserve specific domain details`() {
        val networkErr = AppError.NetworkError("Timeout", httpCode = 408)
        assertEquals("Timeout", networkErr.message)
        assertEquals(408, networkErr.httpCode)

        val authErr = AppError.AuthError("Token expired", isTokenExpired = true)
        assertTrue(authErr.isTokenExpired)

        val valErr = AppError.ValidationError("Invalid phone number", field = "phone")
        assertEquals("phone", valErr.field)

        val storageErr = AppError.StorageError("Disk full")
        assertEquals("Disk full", storageErr.message)

        val deviceErr = AppError.DeviceNotSupportedError("Android 7 not supported")
        assertEquals("Android 7 not supported", deviceErr.message)
    }
}
