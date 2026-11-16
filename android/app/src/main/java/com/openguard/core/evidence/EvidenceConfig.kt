package com.openguard.core.evidence

/** Evidence behaviour constants (Workstream A). */
object EvidenceConfig {

    /** Clip length: 12 seconds, inside the 10-15s spec. */
    const val CLIP_DURATION_S = 12
    const val CLIP_DURATION_MS = 12_000L

    /** How long to wait for CameraX to finish writing a clip after stop. */
    const val FINALIZE_TIMEOUT_MS = 10_000L

    /** Storage cap: newest 50 clips or 500 MB total, oldest deleted first. */
    const val MAX_CLIPS = 50
    const val MAX_TOTAL_BYTES = 500L * 1024 * 1024

    /** Camera values, matching the server's evidence_clips CHECK. */
    const val CAMERA_FRONT = "front"
    const val CAMERA_BACK = "back"
    const val CAMERA_AUDIO = "audio"

    /** Storage values, matching the server's evidence_clips CHECK. */
    const val STORAGE_LOCAL = "local"
    const val STORAGE_DRIVE = "drive"

    /** Row state before the metadata post: not yet local-or-drive. */
    const val STORAGE_PENDING = "pending"

    /** Notification channel + id for the always-visible evidence notification. */
    const val EVIDENCE_CHANNEL_ID = "openguard-evidence"
    // Notification ids are per-app, not per-channel: 7 is the Bluetooth
    // prompt (Constants.BLUETOOTH_NOTIFICATION_ID), so evidence uses 8.
    const val EVIDENCE_NOTIFICATION_ID = 8
}
