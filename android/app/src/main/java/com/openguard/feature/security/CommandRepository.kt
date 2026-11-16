package com.openguard.feature.security

import com.openguard.core.common.Result
import com.openguard.core.network.ApiService
import com.openguard.core.network.CommandDto
import com.openguard.core.network.CreateCommandRequest
import com.openguard.core.network.SecurityEventDto
import com.openguard.core.network.apiCall
import java.util.UUID

/**
 * User-token API for remote commands + security events (the device-token
 * calls live in UploadWorker / CommandExecutor instead).
 */
class CommandRepository(private val api: ApiService) {

    /** A fresh idempotency key per send: retries never double-issue. */
    suspend fun createCommand(
        deviceId: String,
        action: String,
        message: String? = null,
    ): Result<CommandDto> = apiCall {
        api.createCommand(
            deviceId,
            CreateCommandRequest(
                action = action,
                payload = message?.takeIf { it.isNotBlank() }?.let { mapOf("message" to it) },
                idempotencyKey = UUID.randomUUID().toString(),
            ),
        )
    }

    suspend fun listCommands(deviceId: String, limit: Int = 20): Result<List<CommandDto>> =
        when (val result = apiCall { api.listCommands(deviceId, limit) }) {
            is Result.Success -> Result.Success(result.value.commands)
            is Result.Error -> result
        }

    suspend fun listSecurityEvents(deviceId: String, limit: Int = 50): Result<List<SecurityEventDto>> =
        when (val result = apiCall { api.listSecurityEvents(deviceId, limit) }) {
            is Result.Success -> Result.Success(result.value.events)
            is Result.Error -> result
        }
}
