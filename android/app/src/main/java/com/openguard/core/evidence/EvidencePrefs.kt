package com.openguard.core.evidence

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Plain (unencrypted) SharedPreferences for evidence state. Nothing here
 * is a secret: the recording interval and the Drive folder id. The Drive
 * refresh token lives in DriveAuth's encrypted prefs, never here.
 *
 * Settings UI (owned by the coordinator) reads/writes the interval
 * through [intervalMinutes] / [setIntervalMinutes]: 2 or 5 minutes,
 * default 5.
 */
private fun evidencePrefs(context: Context): SharedPreferences =
    context.getSharedPreferences("openguard_evidence", Context.MODE_PRIVATE)

object EvidencePrefs {

    /** Recording interval in minutes: 2 or 5. Default 5. */
    const val DEFAULT_INTERVAL_MINUTES = 5

    fun intervalMinutes(context: Context): Int {
        val value = evidencePrefs(context).getInt(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
        return if (value == 2 || value == 5) value else DEFAULT_INTERVAL_MINUTES
    }

    fun intervalMs(context: Context): Long = intervalMinutes(context) * 60_000L

    fun setIntervalMinutes(context: Context, minutes: Int) {
        if (minutes != 2 && minutes != 5) return
        evidencePrefs(context).edit { putInt(KEY_INTERVAL_MINUTES, minutes) }
    }

    /** Id of the "OpenGuard Evidence" folder in the owner's Drive. Null = unknown yet. */
    fun driveFolderId(context: Context): String? =
        evidencePrefs(context).getString(KEY_DRIVE_FOLDER_ID, null)

    fun setDriveFolderId(context: Context, folderId: String) {
        evidencePrefs(context).edit { putString(KEY_DRIVE_FOLDER_ID, folderId) }
    }

    fun clearDriveFolderId(context: Context) {
        evidencePrefs(context).edit { remove(KEY_DRIVE_FOLDER_ID) }
    }

    private const val KEY_INTERVAL_MINUTES = "evidence_interval_minutes"
    private const val KEY_DRIVE_FOLDER_ID = "drive_folder_id"
}
