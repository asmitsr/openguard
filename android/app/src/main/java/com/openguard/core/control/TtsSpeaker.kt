package com.openguard.core.control

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.openguard.core.common.SecurityConfig
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

// Speaks one owner message aloud on this phone (TextToSpeech).
// Docs: developer.android.com/reference/android/speech/tts/TextToSpeech.
// No permission needed. The engine binds on the main thread, so the
// whole init/speak/wait runs there while the worker suspends; the
// call times out (45s) instead of hanging the worker if the engine
// never answers. No engine installed, or no usable voice, is reported
// as a failed command with the exact reason.
object TtsSpeaker {

    suspend fun speak(context: Context, message: String): RemoteControls.ControlResult {
        val result = withTimeoutOrNull(SecurityConfig.TTS_SPEAK_TIMEOUT_MS) {
            withContext(Dispatchers.Main) { speakInternal(context, message) }
        }
        return result ?: RemoteControls.ControlResult(
            false,
            "Spoken message timed out — the speech engine did not respond",
        )
    }

    private suspend fun speakInternal(
        context: Context,
        message: String,
    ): RemoteControls.ControlResult {
        val tts = suspendCancellableCoroutine<TextToSpeech?> { cont ->
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context.applicationContext) { status ->
                if (!cont.isActive) return@TextToSpeech
                if (status == TextToSpeech.SUCCESS) {
                    cont.resume(engine)
                } else {
                    engine?.shutdown()
                    cont.resume(null)
                }
            }
            cont.invokeOnCancellation { engine?.shutdown() }
        } ?: return RemoteControls.ControlResult(false, "No text-to-speech engine on this phone")
        try {
            val language = tts.setLanguage(Locale.US)
            if (language == TextToSpeech.LANG_MISSING_DATA ||
                language == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                val fallback = tts.setLanguage(Locale.getDefault())
                if (fallback == TextToSpeech.LANG_MISSING_DATA ||
                    fallback == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    return RemoteControls.ControlResult(
                        false,
                        "No usable voice on this phone — no text-to-speech voice installed",
                    )
                }
            }
            tts.setSpeechRate(1.0f)
            val spoken = suspendCancellableCoroutine<Boolean> { cont ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onError(utteranceId: String?) {
                        if (cont.isActive) cont.resume(false)
                    }
                })
                if (tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "openguard-lost") !=
                    TextToSpeech.SUCCESS
                ) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            return if (spoken) {
                RemoteControls.ControlResult(true, "Spoken message played at full volume")
            } else {
                RemoteControls.ControlResult(false, "The speech engine could not speak the message")
            }
        } finally {
            tts.shutdown()
        }
    }
}
