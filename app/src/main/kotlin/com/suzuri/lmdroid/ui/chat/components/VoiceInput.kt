package com.suzuri.lmdroid.ui.chat.components

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Drives the composer's mic button. [isAvailable] reflects whether this device even has a speech
 * recognition service to talk to (some devices — custom ROMs, region-restricted builds — don't);
 * the caller is expected to show its own message when it's false, since that's a one-time device
 * check rather than a per-tap error.
 */
class VoiceInputState internal constructor(private val recognizer: SpeechRecognizer?) {
    var isListening by mutableStateOf(false)
        internal set

    val isAvailable: Boolean get() = recognizer != null

    fun start() {
        val recognizer = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.startListening(intent)
    }

    fun stop() {
        recognizer?.stopListening()
    }
}

/**
 * Wraps Android's on-device [SpeechRecognizer]: recognized speech is handed to [onResult] as
 * plain text, fed straight into the message input — the model itself never sees audio, so this
 * behaves identically no matter which model is selected for chat. [onError] fires for anything
 * that stops listening without a usable result (permission denied mid-flow, recognizer service
 * error, etc.) — not for plain silence/no-speech, which just quietly stops listening. [onPartialResult]
 * is best-effort, interim text that arrives while still listening (e.g. for a live "as you speak"
 * transcript like the assistant overlay) — most callers can ignore it.
 */
@Composable
fun rememberVoiceInputState(
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
    onPartialResult: (String) -> Unit = {},
): VoiceInputState {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnPartialResult by rememberUpdatedState(onPartialResult)

    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    val state = remember(recognizer) { VoiceInputState(recognizer) }

    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    state.isListening = true
                }

                override fun onResults(results: Bundle?) {
                    state.isListening = false
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        currentOnResult(text)
                    }
                }

                override fun onError(error: Int) {
                    state.isListening = false
                    // No match / timeout just means "didn't catch anything" — not worth an error.
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        currentOnError("音声入力でエラーが発生しました。")
                    }
                }

                override fun onEndOfSpeech() {
                    state.isListening = false
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        currentOnPartialResult(text)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )
        onDispose { recognizer?.destroy() }
    }

    return state
}
