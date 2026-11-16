package com.openguard.core.location

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.time.Instant

/**
 * One snapshot of this phone's health, collected for the heartbeat.
 * Every field is best-effort: a null means the value could not be read
 * on this device, and the server keeps the last stored value.
 *
 * No permission is needed for any of these reads: StatFs and
 * ActivityManager are plain system info, the battery intent is sticky
 * (no receiver, no manifest entry), and the notification check is the
 * same permission check the consent flow already uses.
 */
data class DeviceHealth(
    val storageTotalBytes: Long?,
    val storageFreeBytes: Long?,
    val ramTotalBytes: Long?,
    val ramFreeBytes: Long?,
    val batteryTemperatureC: Float?,
    val bootedAt: String?,
    val notificationsEnabled: Boolean,
)

object DeviceHealthCollector {

    fun collect(context: Context): DeviceHealth {
        val (storageTotal, storageFree) = storageBytes()
        val (ramTotal, ramFree) = ramBytes(context)
        return DeviceHealth(
            storageTotalBytes = storageTotal,
            storageFreeBytes = storageFree,
            ramTotalBytes = ramTotal,
            ramFreeBytes = ramFree,
            batteryTemperatureC = batteryTemperatureC(context),
            bootedAt = bootedAt(),
            notificationsEnabled = TrackingPermissions.hasNotifications(context),
        )
    }

    /** Internal storage (the data partition the app lives on). */
    private fun storageBytes(): Pair<Long?, Long?> = runCatching {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        stat.totalBytes to stat.availableBytes
    }.getOrDefault(null to null)

    /** Total + available RAM, as reported by the kernel. */
    private fun ramBytes(context: Context): Pair<Long?, Long?> = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        info.totalMem to info.availMem
    }.getOrDefault(null to null)

    /**
     * Battery temperature in Celsius. The sticky ACTION_BATTERY_CHANGED
     * intent carries EXTRA_TEMPERATURE in tenths of a degree Celsius,
     * on every API level (no receiver is registered for this query).
     */
    private fun batteryTemperatureC(context: Context): Float? = runCatching {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }.getOrNull()

    /** Wall-clock time of the last boot: now minus time since boot. */
    private fun bootedAt(): String =
        Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime()).toString()
}
