package com.suzuri.lmdroid.ui.chat.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import com.suzuri.lmdroid.LmDroidApplication
import com.suzuri.lmdroid.data.vosk.VoskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Recognizer
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
 * Local version of VoiceInputState that uses Vosk for on-device recognition.
 */
class LocalVoiceInputState(
    private val context: Context,
    private val voskRepository: VoskRepository,
    private val onResult: (String) -> Unit,
    private val onPartialResult: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    var isListening by mutableStateOf(false)
        private set

    private var job: Job? = null

    val isAvailable: Boolean get() = true

    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        if (isListening) return
        isListening = true
        job = scope.launch(Dispatchers.IO) {
            try {
                val model = voskRepository.getModel() ?: run {
                    launch(Dispatchers.Main) { onError(context.getString(com.suzuri.lmdroid.R.string.chat_voice_input_error_model)) }
                    isListening = false
                    return@launch
                }
                
                val recognizer = Recognizer(model, 16000.0f)
                val bufferSize = 8000
                val minBufSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    launch(Dispatchers.Main) { onError(context.getString(com.suzuri.lmdroid.R.string.chat_voice_input_permission_denied)) }
                    isListening = false
                    return@launch
                }

                val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize.coerceAtLeast(bufferSize))

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    launch(Dispatchers.Main) { onError(context.getString(com.suzuri.lmdroid.R.string.chat_voice_input_error_mic)) }
                    isListening = false
                    return@launch
                }

                recorder.startRecording()
                val buffer = ShortArray(bufferSize)
                
                try {
                    while (isListening) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            if (recognizer.acceptWaveForm(buffer, read)) {
                                val result = JSONObject(recognizer.result).getString("text")
                                if (result.isNotBlank()) {
                                    launch(Dispatchers.Main) { onResult(result) }
                                    isListening = false // Stop after a final result
                                }
                            } else {
                                val partial = JSONObject(recognizer.partialResult).getString("partial")
                                if (partial.isNotBlank()) {
                                    launch(Dispatchers.Main) { onPartialResult(partial) }
                                }
                            }
                        }
                    }
                } finally {
                    recorder.stop()
                    recorder.release()
                    recognizer.close()
                }
            } catch (e: Exception) {
                Log.e("LocalVoiceInput", "Recognition error", e)
                launch(Dispatchers.Main) { onError(context.getString(com.suzuri.lmdroid.R.string.chat_voice_input_error)) }
            } finally {
                launch(Dispatchers.Main) { isListening = false }
            }
        }
    }

    fun stop() {
        isListening = false
        job?.cancel()
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
                        currentOnError(context.getString(com.suzuri.lmdroid.R.string.chat_voice_input_error))
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

@Composable
fun rememberLocalVoiceInputState(
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
    onPartialResult: (String) -> Unit = {},
): LocalVoiceInputState {
    val context = LocalContext.current
    val voskRepository = (context.applicationContext as LmDroidApplication).container.voskRepository
    
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnPartialResult by rememberUpdatedState(onPartialResult)

    val state = remember {
        LocalVoiceInputState(
            context = context,
            voskRepository = voskRepository,
            onResult = { currentOnResult(it) },
            onPartialResult = { currentOnPartialResult(it) },
            onError = { currentOnError(it) }
        )
    }

    DisposableEffect(Unit) {
        onDispose { state.stop() }
    }

    return state
}
