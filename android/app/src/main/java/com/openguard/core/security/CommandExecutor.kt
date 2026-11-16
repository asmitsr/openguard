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

    private val KNOWN_ACTIONS = setOf("ring", "lock", "lost_mode_on", "lost_mode_off", "erase")

    suspend fun execute(context: Context, command: CommandDto): Outcome {
        if (command.id.isBlank()) return Outcome("failed", "Missing command id")
        val nonce = command.nonce ?: ""
        if (SecurityPrefs.hasExecutedCommand(context, command.id, nonce)) {
            return Outcome("acked", "Duplicate — already executed, replay ignored")
        }
        val outcome = when (command.action) {
            "ring" -> {
                if (RingService.start(context)) {
                    Outcome("acked", "Verified — Ringing")
                } else {
                    Outcome(
                        "failed",
                        "Could not start ringing — open the app or turn tracking on first",
                    )
                }
            }
            "lock" -> {
                if (DeviceAdminManager.lockNow(context)) {
                    Outcome("acked", "Verified — Device locked")
                } else {
                    Outcome("failed", "Device admin is not enabled on this phone")
                }
            }
            "lost_mode_on" -> {
                // Payload is null or JsonNull for every other action, so
                // only read `message` when it is actually an object.
                val message = command.payload
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.get("message")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                Outcome("acked", "Verified — " + LostMode.turnOn(context, message))
            }
            "lost_mode_off" -> {
                LostMode.turnOff(context)
                Outcome("acked", "Verified — Lost Mode off")
            }
            "erase" -> {
                // Erase never wipes at once: without device admin it fails,
                // otherwise it starts the 60-second on-phone countdown.
                if (!DeviceAdminManager.isAdminActive(context)) {
                    Outcome("failed", "Device admin is not enabled — erase needs it")
                } else {
                    EraseCountdown.start(context, command.id)
                    Outcome(
                        "acked",
                        "Verified — erase countdown started, 60 seconds to abort on the phone",
                    )
                }
            }
            else -> Outcome("failed", "Failed verification — unknown action: ${command.action}")
        }
        if (command.action in KNOWN_ACTIONS) {
            SecurityPrefs.addExecutedCommand(context, command.id, nonce)
        }
        return outcome
    }
}
