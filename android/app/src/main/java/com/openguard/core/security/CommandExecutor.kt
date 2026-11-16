package com.openguard.core.security

import android.content.Context
import com.openguard.core.network.CommandDto

/**
 * Executes one pending remote command on this phone. The worker calls
 * this, then acks the command with the returned outcome.
 *
 * Replay protection: executed command ids + nonces are stored in
 * SecurityPrefs and never executed twice, even if the server delivers
 * the same command again.
 */
object CommandExecutor {

    data class Outcome(val status: String, val result: String)

    private val KNOWN_ACTIONS = setOf("ring", "lock", "lost_mode_on", "lost_mode_off")

    suspend fun execute(context: Context, command: CommandDto): Outcome {
        if (command.id.isBlank()) return Outcome("failed", "Missing command id")
        val nonce = command.nonce ?: ""
        if (SecurityPrefs.hasExecutedCommand(context, command.id, nonce)) {
            return Outcome("acked", "Already executed — replay ignored")
        }
        val outcome = when (command.action) {
            "ring" -> {
                if (RingService.start(context)) {
                    Outcome("acked", "Ringing")
                } else {
                    Outcome(
                        "failed",
                        "Could not start ringing — open the app or turn tracking on first",
                    )
                }
            }
            "lock" -> {
                if (DeviceAdminManager.lockNow(context)) {
                    Outcome("acked", "Device locked")
                } else {
                    Outcome("failed", "Device admin is not enabled on this phone")
                }
            }
            "lost_mode_on" -> {
                val message = command.payload
                    ?.get("message")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                Outcome("acked", LostMode.turnOn(context, message))
            }
            "lost_mode_off" -> {
                LostMode.turnOff(context)
                Outcome("acked", "Lost Mode off")
            }
            else -> Outcome("failed", "Unknown action: ${command.action}")
        }
        if (command.action in KNOWN_ACTIONS) {
            SecurityPrefs.addExecutedCommand(context, command.id, nonce)
        }
        return outcome
    }
}
