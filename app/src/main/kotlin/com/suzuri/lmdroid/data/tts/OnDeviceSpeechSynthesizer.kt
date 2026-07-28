package com.suzuri.lmdroid.data.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Wraps Android's on-device TextToSpeech engine — the default speech backend for the assistant
 * overlay (see AssistSpeechPlayer) whenever no VOICEVOX-compatible profile is selected: free,
 * always available, no setup. Lazily initialized on first use and kept alive for the app's
 * lifetime, since creating/tearing down TextToSpeech per utterance is slow and unnecessary.
 */
class OnDeviceSpeechSynthesizer(private val context: Context) {

    private var textToSpeech: TextToSpeech? = null
    private var initSucceeded = false

    private suspend fun ensureInitialized(): TextToSpeech? {
        textToSpeech?.let { if (initSucceeded) return it }

        return suspendCancellableCoroutine { continuation ->
            var created: TextToSpeech? = null
            created = TextToSpeech(context) { status ->
                initSucceeded = status == TextToSpeech.SUCCESS
                if (initSucceeded) {
                    val languageResult = created?.setLanguage(Locale.getDefault())
                    if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "ensureInitialized: ${Locale.getDefault()} not supported by the device TTS voice, using its default")
                    }
                } else {
                    Log.w(TAG, "ensureInitialized: TextToSpeech init failed with status=$status")
                }
                if (continuation.isActive) continuation.resume(if (initSucceeded) created else null)
            }
            textToSpeech = created
        }
    }

    /**
     * Suspends until the utterance finishes, successfully or not — the caller can await this to
     * know when it's safe to, e.g., re-enable the mic for a follow-up question. Never throws; a
     * device with no usable TTS voice at all just silently produces no audio.
     */
    suspend fun speak(text: String) {
        val tts = ensureInitialized()
        if (tts == null) {
            Log.w(TAG, "speak: TextToSpeech unavailable, skipping")
            return
        }
        val utteranceId = UUID.randomUUID().toString()
        suspendCancellableCoroutine { continuation ->
            fun finish() {
                if (continuation.isActive) continuation.resume(Unit)
            }
            tts.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = finish()

                    @Suppress("DEPRECATION") // The (utteranceId, errorCode) overload below is the one actually used; this is only required because the base class still declares it abstract.
                    override fun onError(utteranceId: String?) = finish()

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.w(TAG, "speak: utterance error code=$errorCode")
                        finish()
                    }
                },
            )
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                Log.w(TAG, "speak: TextToSpeech.speak() returned ERROR")
                finish()
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { tts.stop() }
        }
    }

    fun stop() {
        textToSpeech?.stop()
    }

    private companion object {
        const val TAG = "OnDeviceSpeechSynthesizer"
    }
}
