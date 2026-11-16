package com.openguard.core.common

import java.time.Instant

/**
 * Human-friendly relative time for ISO timestamps ("just now",
 * "5 min ago", "2 h ago", "3 d ago"). Mirrors the dashboard's relTime()
 * exactly (same thresholds), so the app and the web UI agree.
 * Null means never seen; an unparsable value is shown as-is.
 */
fun relativeTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "never"
    val at = runCatching { Instant.parse(iso) }.getOrNull() ?: return iso
    val seconds = (System.currentTimeMillis() - at.toEpochMilli()) / 1000
    if (seconds < 60) return "just now"
    if (seconds < 3600) return "${seconds / 60} min ago"
    if (seconds < 86400) return "${seconds / 3600} h ago"
    return "${seconds / 86400} d ago"
}
