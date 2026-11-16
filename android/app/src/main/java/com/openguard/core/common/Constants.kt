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
