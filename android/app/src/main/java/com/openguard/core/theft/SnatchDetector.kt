package com.openguard.core.theft

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.openguard.MainActivity
import com.openguard.R
import com.openguard.core.common.SecurityConfig
import com.openguard.core.security.LostMode
import com.openguard.core.security.LostModeStore
import kotlin.math.sqrt

/**
 * Snatch sensitivity: how easily the detector fires. Higher sensitivity
 * lowers the spike threshold, loosens the calm-before gate, and needs
 * less sustained motion to confirm — it catches gentler grabs but
 * false-positives more easily. Default MEDIUM.
 */
enum class SnatchSensitivity(
    /** Acceleration magnitude (m/s2) that arms the detector. */
    val spikeThresholdMs2: Float,
    /** Pre-spike 2 s mean deviation from gravity must stay below this. */
    val calmMaxDeviation: Float,
    /** Per-sample deviation from gravity that counts as "moving". */
    val motionThresholdMs2: Float,
    /** Fraction of confirmation-window samples that must be moving. */
    val motionFraction: Float,
) {
    LOW(spikeThresholdMs2 = 35f, calmMaxDeviation = 3f, motionThresholdMs2 = 5f, motionFraction = 0.6f),
    MEDIUM(spikeThresholdMs2 = 28f, calmMaxDeviation = 4f, motionThresholdMs2 = 4f, motionFraction = 0.5f),
    HIGH(spikeThresholdMs2 = 22f, calmMaxDeviation = 5f, motionThresholdMs2 = 3f, motionFraction = 0.4f);

    companion object {
        fun fromStored(value: String?): SnatchSensitivity =
            try {
                value?.let { valueOf(it) } ?: MEDIUM
            } catch (e: IllegalArgumentException) {
                MEDIUM
            }
    }
}

/**
 * Snatch detection (accelerometer heuristic): when someone grabs the
 * phone out of the owner's hand and runs, the phone locks itself via
 * Lost Mode before the thief can do anything. This is a heuristic, not
 * a classifier — the on-device LiteRT model is a later phase.
 *
 * The signature is three stages, and ALL three must hold:
 * 1. CALM BEFORE: over the 2 s before the spike, the phone was fairly
 *    still (mean deviation from gravity below the calm gate). This is
 *    what excludes the owner running WITH the phone: a runner's hand
 *    is already moving hard, so the gate fails and nothing arms.
 * 2. SPIKE: one sample exceeds the spike threshold (28 m/s2 at Medium,
 *    just under 3 g). Resting is 9.81, walking peaks at 12-16, running
 *    hand-held peaks at 15-25; a violent yank regularly exceeds 3 g.
 * 3. SUSTAINED MOTION: over the 3 s after the spike, at least half the
 *    samples keep moving (deviation above the motion threshold) AND the
 *    last second is not still. This is what excludes a drop or knock:
 *    one spike then stillness (phone lying on the floor, deviation near
 *    zero) never confirms.
 *
 * On detection: LostMode.turnOn (decoy, evidence, theft response all
 * come along), a high-priority local notification, and a 5-minute
 * cooldown (the phone is already in Lost Mode anyway). No new server
 * event type: the audit trail is the Lost Mode activation itself — see
 * the report note on how local activations reach the server.
 *
 * HOST: LocationService (tracking must be ON — the detector only runs
 * while the tracking foreground service is alive, so the process is
 * not kept alive for this alone). start/stop are idempotent; the
 * ViewModel also calls them when the owner toggles the setting while
 * tracking is already running, so the change takes effect at once.
 *
 * COST: SENSOR_DELAY_GAME is about 50 Hz. That rate is needed — a yank
 * lasts 100-300 ms, and SENSOR_DELAY_NORMAL (5 Hz) samples every
 * 200 ms, so it could miss the spike entirely. The accelerometer is a
 * low-power MEMS sensor (a fraction of a milliwatt at 50 Hz, batched
 * by the sensor hub); next to the GPS foreground service already
 * running, the incremental cost is negligible. No wake lock, no
 * continuous CPU work: the per-sample path is three multiplies, a
 * square root, and a ring-buffer append. Prefs are read only at start
 * and on the rare spike path, never per sample.
 *
 * Honest limits: a violent shake of a still phone can false-positive
 * (spike plus motion looks like a grab); a gentle lift with no yank
 * can miss (no spike, nothing arms). A fall while holding the phone
 * can look like a snatch. Lost Mode after a false positive is
 * recoverable: the owner unlocks with the device credential.
 */
object SnatchDetector {

    /** Notification IDs 1-10 are taken (tracking, ring, lost mode, SIM, erase, WiFi offline, Bluetooth, evidence, geofence, offline-auto-lock warning). */
    private const val SNATCH_NOTIFICATION_ID = 11

    private const val PREFS_NAME = "openguard_snatch_detection"
    private const val KEY_ENABLED = "snatch_enabled"
    private const val KEY_SENSITIVITY = "snatch_sensitivity"
    private const val KEY_LAST_DETECTION_AT = "snatch_last_detection_at"

    /** Acceleration of gravity, m/s2. At rest the magnitude reads this. */
    private const val GRAVITY_MS2 = 9.81f

    /** Confirmation window after the spike. */
    private const val CONFIRM_WINDOW_NS = 3_000_000_000L

    /** Calm-before window ahead of the spike. */
    private const val CALM_WINDOW_NS = 2_000_000_000L

    /** Stillness veto window at the end of the confirmation window. */
    private const val STILLNESS_WINDOW_NS = 1_000_000_000L

    /** Mean deviation below this in the last second means "dropped, lying still". */
    private const val STILLNESS_MAX_DEVIATION = 1.5f

    /** Never re-fire within this of the last detection. */
    const val COOLDOWN_MS = 5 * 60 * 1000L

    /** Calm gate needs at least this many samples (about 1 s at 50 Hz). */
    private const val MIN_CALM_SAMPLES = 50

    /** Confirmation needs at least this many samples in the window. */
    private const val MIN_CONFIRM_SAMPLES = 10

    /** Ring capacity: about 5 s at 50 Hz (2 s calm history + 3 s window). */
    private const val RING_CAPACITY = 256

    private var started = false
    private var appContext: Context? = null
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var sensitivity = SnatchSensitivity.MEDIUM

    /** Wall-clock millis of the last fire (cooldown + "last detection"). */
    private var lastFireAtMs = 0L

    // Ring buffer of recent magnitudes + their sensor timestamps
    // (SensorEvent.timestamp, nanoseconds on the elapsed-realtime clock).
    private val magnitudes = FloatArray(RING_CAPACITY)
    private val timestamps = LongArray(RING_CAPACITY)
    private var ringHead = 0
    private var ringCount = 0

    /** Set when a spike arms the detector; 0 means idle. */
    private var armedAtNs = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val magnitude = sqrt(x * x + y * y + z * z)
            append(event.timestamp, magnitude)
            if (armedAtNs == 0L) {
                maybeArm(event.timestamp, magnitude)
            } else if (event.timestamp - armedAtNs >= CONFIRM_WINDOW_NS) {
                evaluate(event.timestamp)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // Accuracy changes do not affect the heuristic.
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun sensitivity(context: Context): SnatchSensitivity =
        SnatchSensitivity.fromStored(prefs(context).getString(KEY_SENSITIVITY, null))

    fun lastDetectionAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_DETECTION_AT, 0L)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    /**
     * Stores the sensitivity and, if the detector is running, applies
     * the new thresholds at once (no tracking restart needed).
     */
    fun setSensitivity(context: Context, value: SnatchSensitivity) {
        prefs(context).edit { putString(KEY_SENSITIVITY, value.name) }
        sensitivity = value
    }

    /**
     * Starts listening. Idempotent — LocationService calls it on every
     * start intent, and the settings screen calls it when the owner
     * enables detection while tracking is already running. No-op when
     * disabled or when the phone has no accelerometer (rare).
     */
    fun start(context: Context) {
        if (started) return
        if (!isEnabled(context)) return
        val app = context.applicationContext
        val manager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensitivity = sensitivity(context)
        lastFireAtMs = lastDetectionAt(context)
        resetRing()
        appContext = app
        sensorManager = manager
        accelerometer = sensor
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        started = true
    }

    /** Stops listening. Idempotent — safe from onDestroy and the settings screen. */
    fun stop() {
        if (!started) return
        started = false
        try {
            sensorManager?.unregisterListener(listener)
        } catch (e: IllegalArgumentException) {
            // Listener was never registered; nothing to unregister.
        }
        sensorManager = null
        accelerometer = null
        appContext = null
        armedAtNs = 0L
    }

    private fun resetRing() {
        ringHead = 0
        ringCount = 0
        armedAtNs = 0L
    }

    private fun append(timestampNs: Long, magnitude: Float) {
        magnitudes[ringHead] = magnitude
        timestamps[ringHead] = timestampNs
        ringHead = (ringHead + 1) % RING_CAPACITY
        if (ringCount < RING_CAPACITY) ringCount++
    }

    /**
     * Stage 1 + 2: a spike arms the detector only if the phone was calm
     * before it, Lost Mode is not already on, and the cooldown has
     * passed. The Lost Mode and cooldown checks run here — on the rare
     * spike path — never on the per-sample path.
     */
    private fun maybeArm(spikeNs: Long, magnitude: Float) {
        if (magnitude <= sensitivity.spikeThresholdMs2) return
        val context = appContext ?: return
        if (LostModeStore.isOn(context)) return
        if (System.currentTimeMillis() - lastFireAtMs < COOLDOWN_MS) return
        val calm = meanDeviation(spikeNs - CALM_WINDOW_NS, spikeNs)
        if (calm == null || calm.count < MIN_CALM_SAMPLES) return
        if (calm.mean >= sensitivity.calmMaxDeviation) return
        armedAtNs = spikeNs
    }

    /**
     * Stage 3: at the end of the confirmation window, fire only if the
     * phone kept moving through the window and is not lying still now.
     * Either way the detector disarms — a new spike starts a new
     * evaluation.
     */
    private fun evaluate(nowNs: Long) {
        val startNs = armedAtNs
        armedAtNs = 0L
        val context = appContext ?: return
        val window = samplesIn(startNs, nowNs)
        if (window.size < MIN_CONFIRM_SAMPLES) return
        var moving = 0
        for (magnitude in window) {
            if (kotlin.math.abs(magnitude - GRAVITY_MS2) > sensitivity.motionThresholdMs2) moving++
        }
        if (moving.toFloat() / window.size < sensitivity.motionFraction) return
        val stillness = meanDeviation(nowNs - STILLNESS_WINDOW_NS, nowNs)
        if (stillness != null && stillness.mean < STILLNESS_MAX_DEVIATION) return
        fire(context)
    }

    private fun fire(context: Context) {
        val now = System.currentTimeMillis()
        lastFireAtMs = now
        prefs(context).edit { putLong(KEY_LAST_DETECTION_AT, now) }
        postNotification(context)
        LostMode.turnOn(context, "Snatch detected — auto-locked")
    }

    /** Mean absolute deviation from gravity over [fromNs, toNs), or null when empty. */
    private fun meanDeviation(fromNs: Long, toNs: Long): DeviationResult? {
        val window = samplesIn(fromNs, toNs)
        if (window.isEmpty()) return null
        var sum = 0f
        for (magnitude in window) sum += kotlin.math.abs(magnitude - GRAVITY_MS2)
        return DeviationResult(sum / window.size, window.size)
    }

    /** Magnitudes with timestamps in [fromNs, toNs), oldest first. Linear scan over at most 256 entries, only on the rare spike/evaluate path. */
    private fun samplesIn(fromNs: Long, toNs: Long): List<Float> {
        val out = ArrayList<Float>(ringCount)
        for (i in 0 until ringCount) {
            val index = (ringHead - ringCount + i + RING_CAPACITY) % RING_CAPACITY
            val timestamp = timestamps[index]
            if (timestamp in fromNs until toNs) out.add(magnitudes[index])
        }
        return out
    }

    /**
     * High-priority local notification: the phone locked itself. Tapping
     * it opens the app. Request code 11, not 0: LocationService already
     * uses request code 0 for a MainActivity PendingIntent, and
     * same-class plus same-request-code PendingIntents collide.
     */
    private fun postNotification(context: Context) {
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
        val openApp = PendingIntent.getActivity(
            context, SNATCH_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, SecurityConfig.SECURITY_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.snatch_notification_title))
            .setContentText(context.getString(R.string.snatch_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            manager.notify(SNATCH_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications revoked: Lost Mode is still on locally.
        }
    }

    private data class DeviationResult(val mean: Float, val count: Int)
}
