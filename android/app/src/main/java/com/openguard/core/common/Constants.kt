package com.openguard.core.common

/** Single place for app-wide configuration. */
object AppConfig {
    /**
     * Base URL of the OpenGuard API (Cloudflare Worker).
     *
     * TODO(parent): replace the `<account>` placeholder with the real deployed
     * URL, e.g. "https://openguard-api.<subdomain>.workers.dev/".
     * This is the ONLY place the API URL is defined. ApiClient refuses to
     * start while the placeholder is still in place, so a forgotten URL
     * fails loudly instead of silently calling a wrong host.
     *
     * Note the trailing slash: Retrofit requires base URLs to end with "/".
     */
    const val API_BASE_URL = "https://openguard-api.asmitsr.workers.dev/"
}

/** Tracking behaviour constants (MVP values, tune later). */
object TrackingConfig {
    /** Desired interval between location fixes. */
    const val LOCATION_INTERVAL_MS = 60_000L

    /** Fastest rate at which fixes are accepted (battery guard). */
    const val LOCATION_MIN_INTERVAL_MS = 30_000L

    /** Minimum movement between fixes. */
    const val LOCATION_MIN_DISTANCE_M = 10f

    /** Points per upload batch. Backend accepts up to 500. */
    const val UPLOAD_BATCH_SIZE = 100

    /** Unique name of the periodic upload work. */
    const val UPLOAD_WORK_NAME = "openguard-location-upload"

    /** Notification channel + id for the always-visible tracking notification. */
    const val TRACKING_CHANNEL_ID = "openguard-tracking"
    const val TRACKING_NOTIFICATION_ID = 1
}

/** Phase 2 security behaviour constants. */
object SecurityConfig {
    /** Events per upload batch. Backend accepts 1..100. */
    const val SECURITY_EVENTS_BATCH_SIZE = 100

    /** Low-battery event: at or below this level, while not charging. */
    const val LOW_BATTERY_THRESHOLD = 15

    /** At most one low-battery event per period. */
    const val LOW_BATTERY_COOLDOWN_MS = 24 * 60 * 60 * 1000L

    /** Sync gap: no successful upload for longer than this. */
    const val SYNC_GAP_MS = 6 * 60 * 60 * 1000L

    /** Ring stops by itself after this long. */
    const val RING_DURATION_MS = 2 * 60 * 1000L

    /** Max characters for the Lost Mode message. */
    const val LOST_MESSAGE_MAX_LENGTH = 200

    const val RING_CHANNEL_ID = "openguard-ring"
    const val RING_NOTIFICATION_ID = 2
    const val LOST_MODE_CHANNEL_ID = "openguard-lost-mode"
    const val LOST_MODE_NOTIFICATION_ID = 3
    const val SECURITY_CHANNEL_ID = "openguard-security"
    const val SIM_NOTIFICATION_ID = 4

    // ---- Phase 3: remote erase ----
    const val ERASE_CHANNEL_ID = "openguard-erase"
    const val ERASE_NOTIFICATION_ID = 5

    /** Grace period between an erase command and the wipe (abort window). */
    const val ERASE_GRACE_MS = 60_000L

    /** Unique name of the one-time backstop worker that wipes if the countdown activity died. */
    const val ERASE_WORK_NAME = "openguard-erase-backstop"
}
