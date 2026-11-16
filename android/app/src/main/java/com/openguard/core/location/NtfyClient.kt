package com.openguard.core.location

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.JsonParser
import com.openguard.OpenGuardApp
import com.openguard.core.network.PushTopicRequest
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Embedded ntfy subscriber: instant push with NO second app to install
 * and NO new dependency (plain OkHttp streaming GET).
 *
 * Mechanism (verified against the ntfy docs): the client opens one
 * long-lived GET to https://ntfy.sh/{topic}/json and reads the
 * newline-delimited JSON stream line by line. Each line is one JSON
 * object with an "event" field ("open", "keepalive", "message",
 * "poll_request"). Only "message" events matter: the body is ignored
 * entirely — ANY message on the topic is just a wake-up, and the device
 * then fetches its pending commands over authenticated TLS exactly as
 * the WorkManager poll does (nonce, expiry and replay checks unchanged
 * in CommandExecutor). The push itself carries only a random opaque
 * wake-up id, never a location and never command content.
 *
 * A UnifiedPush distributor was NOT chosen: it would force the user to
 * install and maintain a second app, while the embedded client does the
 * same job with the OkHttp the app already ships.
 *
 * Runs inside the foreground LocationService (tracking on), so the
 * stream survives background restrictions; it stops with the service.
 * When tracking is off, or ntfy.sh is unreachable, the 15-minute
 * WorkManager poll remains the fallback — commands are never lost, only
 * slower. Reconnects with exponential backoff (5s, doubling to 60s).
 */
class NtfyClient(
    private val scope: CoroutineScope,
    private val onWakeUp: () -> Unit,
) {
    private var streamJob: Job? = null

    @Volatile
    private var currentCall: Call? = null

    // Dedicated client: no auth interceptor — OpenGuard credentials must
    // never be sent to ntfy.sh. Infinite read timeout: the stream stays
    // open for hours; the server's keepalive events prove it is alive.
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    /** Starts the stream loop; no-op when already running. */
    fun start(context: Context) {
        if (streamJob != null) return
        val appContext = context.applicationContext
        streamJob = scope.launch(Dispatchers.IO) {
            var backoffMs = INITIAL_BACKOFF_MS
            while (isActive) {
                val topic = PushTopicStore.topic(appContext)
                if (topic == null) {
                    // Not registered yet (no device, or registration
                    // failed): wait, then retry. The worker poll covers
                    // commands in the meantime.
                    delay(REGISTRATION_RETRY_MS)
                    continue
                }
                val startedAt = System.currentTimeMillis()
                try {
                    stream(topic)
                } catch (e: IOException) {
                    // Dropped connection, DNS failure, ntfy.sh down:
                    // fall through to the backoff below.
                }
                if (!isActive) break
                // A long healthy stream resets the backoff, so a single
                // drop reconnects quickly instead of waiting out a stale
                // 60-second delay.
                if (System.currentTimeMillis() - startedAt > HEALTHY_STREAM_MS) {
                    backoffMs = INITIAL_BACKOFF_MS
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        currentCall?.cancel()
        currentCall = null
    }

    /**
     * Blocks reading the NDJSON stream until EOF or an error. Returns on
     * a clean EOF or a non-2xx response; throws IOException on a
     * dropped connection or a cancelled call.
     */
    private fun stream(topic: String) {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(NTFY_HOST)
            .addPathSegment(topic)
            .addPathSegment("json")
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val call = http.newCall(request)
        currentCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) return
                val source = response.body?.source() ?: return
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    if (isWakeUp(line)) onWakeUp()
                }
            }
        } finally {
            if (currentCall === call) currentCall = null
        }
    }

    /** True only for {"event": "message", ...} lines; everything else is ignored. */
    private fun isWakeUp(line: String): Boolean = runCatching {
        JsonParser.parseString(line).asJsonObject.get("event")?.asString == "message"
    }.getOrDefault(false)

    companion object {
        const val NTFY_BASE_URL = "https://ntfy.sh"

        private const val NTFY_HOST = "ntfy.sh"
        private const val INITIAL_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val REGISTRATION_RETRY_MS = 30_000L

        /** A stream alive this long counts as healthy (resets backoff). */
        private const val HEALTHY_STREAM_MS = 60_000L

        /**
         * Makes sure this device has a topic and the server knows it.
         * Creates the topic on first call, then registers it with one
         * API call; later calls are just preference reads. False when
         * the phone is not registered as a device yet, or the
         * registration request failed — callers retry later (the
         * UploadWorker calls this on every run, best-effort).
         */
        suspend fun ensureRegistered(app: OpenGuardApp): Boolean {
            val context = app.applicationContext
            val deviceId = app.tokenStore.deviceId ?: return false
            val deviceToken = app.tokenStore.deviceToken ?: return false
            val topic = PushTopicStore.getOrCreateTopic(context)
            if (PushTopicStore.registeredTopic(context) == topic) return true
            if (registerTopic(app, deviceId, deviceToken, topic)) return true
            // 409: the topic is held by a stale device row (failed
            // sign-out). Rotate once and retry; a second failure just
            // retries on the next worker run.
            if (lastRegisterCode == 409) {
                val rotated = PushTopicStore.rotate(context)
                return registerTopic(app, deviceId, deviceToken, rotated)
            }
            return false
        }

        /** Last HTTP status from registerTopic (409 drives the rotate above). */
        @Volatile
        private var lastRegisterCode = 0

        private suspend fun registerTopic(
            app: OpenGuardApp,
            deviceId: String,
            deviceToken: String,
            topic: String,
        ): Boolean {
            return try {
                val response = app.api.registerPushTopic(
                    "Bearer $deviceToken", deviceId, PushTopicRequest(topic),
                )
                lastRegisterCode = response.code()
                if (response.isSuccessful) {
                    PushTopicStore.setRegisteredTopic(app.applicationContext, topic)
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                lastRegisterCode = 0
                false
            }
        }
    }
}

/**
 * The per-device ntfy topic, in EncryptedSharedPreferences (same pattern
 * as TokenStore): anyone who knows the topic can publish fake wake-ups
 * to it and subscribe to it, so it is a secret, never in plain prefs
 * and never logged. The server stores the same topic (devices.push_topic)
 * so it knows where to publish; no endpoint ever returns it.
 *
 * Lifecycle: created on first ensureRegistered (random, unguessable),
 * registered once, re-registered after rotation. Rotation (Settings)
 * writes a new topic and clears the registered marker; the next
 * ensureRegistered call registers the new one, and the stream
 * re-subscribes on the next tracking restart.
 */
object PushTopicStore {

    fun topic(context: Context): String? = prefs(context).getString(KEY_TOPIC, null)

    fun getOrCreateTopic(context: Context): String {
        topic(context)?.let { return it }
        val created = generate()
        prefs(context).edit { putString(KEY_TOPIC, created) }
        return created
    }

    fun registeredTopic(context: Context): String? =
        prefs(context).getString(KEY_REGISTERED_TOPIC, null)

    fun setRegisteredTopic(context: Context, topic: String) {
        prefs(context).edit { putString(KEY_REGISTERED_TOPIC, topic) }
    }

    /** Rotates to a fresh topic; returns it (for the 409 retry path). */
    fun rotate(context: Context): String {
        val created = generate()
        prefs(context).edit {
            putString(KEY_TOPIC, created)
            remove(KEY_REGISTERED_TOPIC)
        }
        return created
    }

    /** "og-" + 128 random bits as hex: unguessable, ntfy-safe charset. */
    private fun generate(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return "og-" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun prefs(context: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            FILE_NAME,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private const val FILE_NAME = "openguard_push"
    private const val KEY_TOPIC = "push_topic"
    private const val KEY_REGISTERED_TOPIC = "registered_push_topic"
}
