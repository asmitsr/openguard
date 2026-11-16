package com.openguard.feature.security

import com.openguard.core.common.Result
import com.openguard.core.network.ApiService
import com.openguard.core.network.CommandDto
import com.openguard.core.network.CommandVerifyResponse
import com.openguard.core.network.CreateCommandRequest
import com.openguard.core.network.SecurityEventDto
import com.openguard.core.network.apiCall
import java.util.UUID

/**
 * User-token API for remote commands + security events (the device-token
 * calls live in UploadWorker / CommandExecutor instead).
 */
class CommandRepository(private val api: ApiService) {

    /**
     * A fresh idempotency key per send: retries never double-issue.
     * Payload values are strings (the DTO is Map<String, String>):
     * numbers and booleans travel as their string form, e.g.
     * set_volume takes {"stream": "all", "level": "100"}. Lost Mode
     * passes {"message": "..."}; actions without params pass null.
     */
    suspend fun createCommand(
        deviceId: String,
        action: String,
        payload: Map<String, String>? = null,
        stepUpToken: String? = null,
    ): Result<CommandDto> = apiCall {
        api.createCommand(
            deviceId,
            CreateCommandRequest(
                action = action,
                payload = payload,
                idempotencyKey = UUID.randomUUID().toString(),
                stepUpToken = stepUpToken,
            ),
        )
    }

    /** One command plus its audit timeline, for the verify dialog. */
    suspend fun verifyCommand(commandId: String): Result<CommandVerifyResponse> =
        apiCall { api.verifyCommand(commandId) }

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
