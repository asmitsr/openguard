package com.openguard.core.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.openguard.MainActivity
import com.openguard.R
import com.openguard.core.common.SecurityConfig
import com.openguard.core.database.AppDatabase
import com.openguard.core.security.SecurityEvents
import com.openguard.core.security.SecurityPrefs

/**
 * The feasible maximum for "reconnect to WiFi when offline" on Android
 * 10+. What an app CANNOT do (and this code never claims): turn WiFi on,
 * force a connection, or read the user's saved networks — setWifiEnabled()
 * is a no-op for apps targeting Android 10+, and getConfiguredNetworks()
 * returns nothing useful to a third-party app.
 *
 * What it CAN do, and this object does:
 * - Suggest networks the owner enters (WifiNetworkSuggestion, API 29+).
 *   Android asks the owner ONCE for approval; after that the OS
 *   auto-connects when a suggested network is in range. The passphrase is
 *   handed to the OS and never stored by the app.
 * - Open the system WiFi panel (Settings.Panel, API 29+) in one tap, so
 *   the owner can re-enable WiFi themselves.
 * - Notify the owner when the phone goes offline with WiFi off.
 */
object WifiReconnect {

    /** Suggestions and the WiFi panel both need Android 10 (API 29). */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun isWifiEnabled(context: Context): Boolean =
        (context.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled

    private fun wifiManager(context: Context): WifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * Suggests one network. Blank passphrase means an open network.
     * Returns the WifiManager status code, or UNSUPPORTED below API 29.
     * A duplicate (already suggested) is removed and re-added, so
     * re-suggesting after a password change just works.
     */
    fun suggestNetwork(context: Context, ssid: String, passphrase: String?): Int {
        if (!isSupported()) return UNSUPPORTED
        val cleanSsid = ssid.trim()
        if (cleanSsid.isEmpty() || cleanSsid.length > 32) {
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_INVALID
        }
        val cleanPass = passphrase?.trim().orEmpty()
        if (cleanPass.isNotEmpty() && (cleanPass.length < 8 || cleanPass.length > 63)) {
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_INVALID
        }
        val builder = WifiNetworkSuggestion.Builder().setSsid(cleanSsid)
        if (cleanPass.isNotEmpty()) builder.setWpa2Passphrase(cleanPass)
        val suggestion = builder.build()
        val manager = wifiManager(context)
        val status = manager.addNetworkSuggestions(listOf(suggestion))
        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE) {
            manager.removeNetworkSuggestions(listOf(suggestion))
            return manager.addNetworkSuggestions(listOf(suggestion))
        }
        return status
    }

    /** One short line per add-networks status, for the Settings screen. */
    fun describeAddStatus(status: Int): String = when (status) {
        WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ->
            "Suggested. Android will ask once for approval, then connect automatically when this network is in range."
        WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE ->
            "This network is already suggested."
        WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP ->
            "Too many suggested networks — remove some first."
        WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_INVALID ->
            "Check the network name (1–32 characters) and password (8–63 characters)."
        WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED ->
            "Network suggestions are disabled for OpenGuard in system settings."
        UNSUPPORTED -> "Needs Android 10 or newer."
        else -> "Could not suggest this network."
    }

    /**
     * SSIDs this app has suggested. Read-back needs API 30+; below that
     * the list is empty (the suggestions still work, they just cannot
     * be listed).
     */
    fun suggestedSsids(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return runCatching {
            wifiManager(context).networkSuggestions.map { it.ssid }
        }.getOrDefault(emptyList())
    }

    /** Removes every network this app suggested. */
    fun removeAllSuggestions(context: Context) {
        if (!isSupported()) return
        runCatching { wifiManager(context).removeNetworkSuggestions(emptyList()) }
    }

    /** Opens the system WiFi panel (one-tap WiFi toggle), API 29+. */
    fun openWifiPanel(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Called when connectivity is lost and no network remains (from the
     * LocationService NetworkCallback). Records one sync_gap event per
     * hour — the server's event CHECK has no offline type, so the closest
     * existing type is reused, with the reason in the details — and
     * notifies the owner when WiFi is off. The event sits in the Room
     * outbox and uploads when the phone is back online. No "back online"
     * event: the resumed heartbeat (and last-seen time) already shows it,
     * and onAvailable fires on every handover, which would spam.
     */
    suspend fun onOffline(context: Context, db: AppDatabase) {
        val now = System.currentTimeMillis()
        if (now - SecurityPrefs.lastOfflineEventAt(context) <
            SecurityConfig.OFFLINE_EVENT_COOLDOWN_MS
        ) {
            return
        }
        SecurityPrefs.setLastOfflineEventAt(context, now)
        val wifiOff = !isWifiEnabled(context)
        SecurityEvents.record(
            db, "sync_gap",
            details = mapOf("reason" to "connectivity_lost", "wifi_enabled" to !wifiOff),
            confirmed = true,
        )
        if (wifiOff) postOfflineNotification(context)
    }

    /**
     * High-priority "you are offline" notification, posted only when the
     * phone lost connectivity AND WiFi is off. The action opens the WiFi
     * panel so the owner can turn WiFi on in one tap.
     */
    fun postOfflineNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(SecurityConfig.SECURITY_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    SecurityConfig.SECURITY_CHANNEL_ID,
                    context.getString(R.string.security_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val panelIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val wifiPanel = PendingIntent.getActivity(
            context, 0,
            panelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, SecurityConfig.SECURITY_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.wifi_offline_notification_title))
            .setContentText(context.getString(R.string.wifi_offline_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(openApp)
            .addAction(
                android.R.drawable.ic_menu_manage,
                context.getString(R.string.wifi_offline_notification_action),
                wifiPanel,
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            manager.notify(SecurityConfig.WIFI_OFFLINE_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications revoked: nothing to show.
        }
    }

    /** Returned by suggestNetwork below API 29 (no WifiManager constant). */
    const val UNSUPPORTED = -100
}
