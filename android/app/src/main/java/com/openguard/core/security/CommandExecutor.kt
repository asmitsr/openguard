package com.openguard.core.security

import android.content.Context
import com.openguard.core.control.RemoteControls
import com.openguard.core.evidence.EvidenceService
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

    private val KNOWN_ACTIONS = setOf(
        "ring", "lock", "lost_mode_on", "lost_mode_off", "erase",
        // Workstream B: Remote Control Center.
        "set_volume", "set_brightness", "set_auto_brightness",
        "torch_on", "torch_off", "set_ringer_mode", "set_dnd",
        "set_screen_timeout", "set_auto_rotate", "speak_message",
        "wake_screen", "set_wallpaper", "bluetooth_on", "evidence_capture",
    )

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
            // Workstream B: Remote Control Center. Payload values are
            // strings (the client sends Map<String, String>); every
            // branch defaults defensively so a missing field still
            // does the useful thing instead of failing.
            "set_volume" -> controlOutcome(
                RemoteControls.setVolume(
                    context,
                    payloadString(command, "stream") ?: "all",
                    payloadInt(command, "level", 100),
                ),
            )
            "set_brightness" -> controlOutcome(
                RemoteControls.setBrightness(context, payloadInt(command, "level", 100)),
            )
            "set_auto_brightness" -> controlOutcome(
                RemoteControls.setAutoBrightness(
                    context,
                    payloadBool(command, "enabled", true),
                ),
            )
            "torch_on" -> controlOutcome(RemoteControls.setTorch(context, true))
            "torch_off" -> controlOutcome(RemoteControls.setTorch(context, false))
            "set_ringer_mode" -> controlOutcome(
                RemoteControls.setRingerMode(
                    context,
                    payloadString(command, "mode") ?: "normal",
                ),
            )
            "set_dnd" -> controlOutcome(
                RemoteControls.setDnd(context, payloadString(command, "mode") ?: "off"),
            )
            "set_screen_timeout" -> controlOutcome(
                RemoteControls.setScreenTimeout(context, payloadInt(command, "seconds", 60)),
            )
            "set_auto_rotate" -> controlOutcome(
                RemoteControls.setAutoRotate(context, payloadBool(command, "enabled", true)),
            )
            "speak_message" -> controlOutcome(
                RemoteControls.speakMessage(
                    context,
                    payloadString(command, "message")?.takeIf { it.isNotBlank() }
                        ?: RemoteControls.DEFAULT_SPOKEN_MESSAGE,
                ),
            )
            "wake_screen" -> controlOutcome(RemoteControls.wakeScreen(context))
            "set_wallpaper" -> controlOutcome(
                RemoteControls.setWallpaper(
                    context,
                    payloadString(command, "message")?.takeIf { it.isNotBlank() }
                        ?: RemoteControls.DEFAULT_WALLPAPER_MESSAGE,
                    payloadString(command, "which") ?: "lock",
                ),
            )
            "bluetooth_on" -> controlOutcome(RemoteControls.bluetoothOn(context))
            "evidence_capture" -> {
                // Built by worker A (core/evidence/EvidenceService).
                // It only records while Lost Mode is on (privacy guard:
                // no recording on a phone that is not marked lost), and
                // the foreground start can be denied from the
                // background worker, so both are acked explicitly.
                if (!LostModeStore.isOn(context)) {
                    Outcome(
                        "failed",
                        "Evidence capture needs Lost Mode on — recording only runs " +
                            "while the phone is marked lost",
                    )
                } else {
                    try {
                        EvidenceService.captureNow(context)
                        Outcome("acked", "Verified — Evidence capture started")
                    } catch (e: IllegalStateException) {
                        Outcome(
                            "failed",
                            "Could not start evidence capture — start denied in the background",
                        )
                    } catch (e: SecurityException) {
                        Outcome(
                            "failed",
                            "Could not start evidence capture — microphone permission missing",
                        )
                    }
                }
            }
            else -> Outcome("failed", "Failed verification — unknown action: ${command.action}")
        }
        if (command.action in KNOWN_ACTIONS) {
            SecurityPrefs.addExecutedCommand(context, command.id, nonce)
        }
        return outcome
    }

    // A control result maps onto an ack: success keeps the existing
    // "Verified — " prefix, failure reports the exact missing grant.
    private fun controlOutcome(result: RemoteControls.ControlResult): Outcome =
        if (result.ok) Outcome("acked", "Verified — " + result.text) else Outcome("failed", result.text)

    // Payload is null or JsonNull for actions without one, so only
    // read a field when it is actually an object with a primitive.
    // asString works for numbers too, so a numeric value still parses.
    private fun payloadString(command: CommandDto, key: String): String? =
        command.payload
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get(key)
            ?.takeIf { it.isJsonPrimitive }
            ?.asString

    private fun payloadInt(command: CommandDto, key: String, default: Int): Int =
        payloadString(command, key)?.toIntOrNull() ?: default

    private fun payloadBool(command: CommandDto, key: String, default: Boolean): Boolean =
        payloadString(command, key)?.toBooleanStrictOrNull() ?: default
}
