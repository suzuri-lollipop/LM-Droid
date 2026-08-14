package com.suzuri.lmdroid.ui.chat.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
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
import com.suzuri.lmdroid.data.settings.SettingsRepository
import com.suzuri.lmdroid.data.stt.SpeechEngineType
import com.suzuri.lmdroid.data.stt.SpeechModel
import com.suzuri.lmdroid.data.stt.SpeechModelManager
import com.suzuri.lmdroid.data.stt.WhisperEngine
import com.suzuri.lmdroid.data.vosk.VoskEngine
import com.suzuri.lmdroid.data.vosk.VoskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

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
 * Local version of VoiceInputState that uses on-device recognition (Vosk or Whisper).
 */
class LocalVoiceInputState(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val voskRepository: VoskRepository,
    private val speechModelManager: SpeechModelManager,
    private val onResult: (String) -> Unit,
    private val onPartialResult: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    var isListening by mutableStateOf(false)
        private set

    private var job: Job? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val isAvailable: Boolean get() = true

    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        if (isListening) return
        isListening = true
        job = scope.launch(Dispatchers.IO) {
            try {
                val modelId = settingsRepository.currentSelectedSttModelId()
                val speechModel = SpeechModel.ALL_MODELS.find { it.id == modelId } ?: SpeechModel.VOSK_SMALL_JP

                if (!speechModelManager.isModelAvailable(speechModel)) {
                    launch(Dispatchers.Main) { onError(context.getString(com.suzuri.lmdroid.R.string.chat_voice_input_error_model)) }
                    isListening = false
                    return@launch
                }

                val engine = when (speechModel.engineType) {
                    SpeechEngineType.VOSK -> {
                        val model = voskRepository.getModel(speechModel) ?: run {
                            launch(Dispatchers.Main) { onError(context.getString(com.suzuri.lmdroid.R.string.chat_voice_input_error_model)) }
                            isListening = false
                            return@launch
                        }
                        VoskEngine(model)
                    }
                    SpeechEngineType.WHISPER -> {
                        val modelFile = speechModelManager.getModelDir(speechModel)
                        if (!modelFile.exists()) {
                            launch(Dispatchers.Main) { onError(context.getString(com.suzuri.lmdroid.R.string.chat_voice_input_error_model)) }
                            isListening = false
                            return@launch
                        }
                        WhisperEngine(modelFile.absolutePath)
                    }
                }

                if (!engine.isReady) {
                    launch(Dispatchers.Main) { onError(context.getString(com.suzuri.lmdroid.R.string.chat_voice_input_error)) }
                    isListening = false
                    return@launch
                }
                
                // Route to a Bluetooth headset mic only while one is actually connected.
                // Starting SCO unconditionally leaves the built-in mic silent on devices
                // (e.g. the emulator) that report SCO as available without a headset.
                val btScoConnected = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (btScoConnected) {
                    Log.d("LocalVoiceInput", "Starting Bluetooth SCO for UI listening")
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }

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
                    var buffersRead = 0
                    while (isListening) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            if (buffersRead++ % 2 == 0) {
                                var peak = 0
                                for (i in 0 until read) {
                                    val amplitude = abs(buffer[i].toInt())
                                    if (amplitude > peak) peak = amplitude
                                }
                                Log.d("LocalVoiceInput", "Mic peak amplitude: $peak")
                            }
                            if (engine.acceptAudio(buffer, read)) {
                                val result = engine.getResult()
                                launch(Dispatchers.Main) { 
                                    if (result.isNotBlank()) {
                                        onResult(result) 
                                    }
                                    isListening = false // Stop on any final result (including empty)
                                }
                            } else {
                                val partial = engine.getPartialResult()
                                if (partial.isNotBlank()) {
                                    launch(Dispatchers.Main) { onPartialResult(partial) }
                                }
                            }
                        }
                    }
                } finally {
                    if (audioManager.isBluetoothScoOn) {
                        Log.d("LocalVoiceInput", "Stopping Bluetooth SCO")
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                    }
                    recorder.stop()
                    recorder.release()
                    engine.release()
                }
            } catch (e: Throwable) {
                Log.e("LocalVoiceInput", "Recognition error", e)
                launch(Dispatchers.Main) { 
                    val errorMsg = if (e is UnsatisfiedLinkError) {
                        "Native library failed to load. Please try restarting the app."
                    } else {
                        context.getString(com.suzuri.lmdroid.R.string.chat_voice_input_error)
                    }
                    onError(errorMsg) 
                }
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
    val container = (context.applicationContext as LmDroidApplication).container
    val voskRepository = container.voskRepository
    val settingsRepository = container.settingsRepository
    val speechModelManager = container.speechModelManager
    
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnPartialResult by rememberUpdatedState(onPartialResult)

    val state = remember {
        LocalVoiceInputState(
            context = context,
            settingsRepository = settingsRepository,
            voskRepository = voskRepository,
            speechModelManager = speechModelManager,
            onResult = { currentOnResult(it) },
            onPartialResult = { currentOnPartialResult(it) },
            onError = { currentOnError(it) },
        )
    }

    DisposableEffect(Unit) {
        onDispose { state.stop() }
    }

    return state
}
