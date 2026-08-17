package com.suzuri.lmdroid.ui.chat.components

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
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
import androidx.core.content.ContextCompat
import com.suzuri.lmdroid.LmDroidApplication
import com.suzuri.lmdroid.data.audio.normalizedPeakLevel
import com.suzuri.lmdroid.data.settings.SettingsRepository
import com.suzuri.lmdroid.data.stt.SpeechEngineType
import com.suzuri.lmdroid.data.stt.SpeechModel
import com.suzuri.lmdroid.data.stt.SpeechModelManager
import com.suzuri.lmdroid.data.stt.WhisperEngine
import com.suzuri.lmdroid.data.vosk.VoskEngine
import com.suzuri.lmdroid.data.vosk.VoskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

// How long the "start listening" beep plays (see LocalVoiceInputState.start) — also used as the
// delay before the mic actually starts capturing, so the beep can't leak into the recognized audio.
private const val START_LISTENING_BEEP_DURATION_MS = 150

/**
 * Suspends until the Bluetooth SCO link [android.media.AudioManager.startBluetoothSco] just
 * requested is actually up (ACTION_SCO_AUDIO_STATE_UPDATED / SCO_AUDIO_STATE_CONNECTED), or
 * [timeoutMs] elapses — establishing SCO is asynchronous and takes up to ~2s, and audio
 * (recording or playback) started before it connects can be silently dropped or misrouted.
 */
private suspend fun awaitBluetoothScoConnected(context: Context, timeoutMs: Long = 2000L): Boolean {
    return withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<Unit> { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    if (intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                        context.unregisterReceiver(this)
                        if (cont.isActive) cont.resume(Unit, onCancellation = null)
                    }
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            cont.invokeOnCancellation {
                try { context.unregisterReceiver(receiver) } catch (e: IllegalArgumentException) { /* already unregistered */ }
            }
        }
    } != null
}

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
 * [isPreparing] covers the wind-up between [start] and the microphone actually recording (model
 * loading can take seconds), so callers can hold their "speak now" prompt until [isListening].
 * [onResult] is called for every finished recognition, *including* a blank one — blank means the
 * session ended without usable speech, and callers rely on it to drop a stale partial transcript.
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

    var isPreparing by mutableStateOf(false)
        private set

    // True between end-of-utterance detection and the final result landing (see
    // SpeechRecognizerEngine.isFinalizing) — the mic is effectively done, but the final
    // inference still runs for seconds on-device; the UI shows "recognizing…" in this window
    // instead of prompting the user to keep speaking.
    var isFinalizing by mutableStateOf(false)
        private set

    private var job: Job? = null
    // Bumped on every start() and stop(); callbacks capture the value of the session that
    // produced them and are dropped if it no longer matches. Whisper's final inference finishes
    // seconds after the utterance ends, so a session stopped in the meantime (overlay dismissed,
    // a retry/retrigger starting a fresh one) would otherwise still deliver — and auto-send — the
    // stale transcript once it lands. Volatile because the capture loop reads it on the IO
    // dispatcher while start()/stop() bump it on Main.
    @Volatile
    private var generation = 0
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val isAvailable: Boolean get() = true

    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        if (isListening || isPreparing) return
        isPreparing = true
        val sessionGeneration = ++generation
        job = scope.launch(Dispatchers.IO) {
            try {
                val modelId = settingsRepository.currentSelectedSttModelId()
                val speechModel = SpeechModel.ALL_MODELS.find { it.id == modelId } ?: SpeechModel.VOSK_SMALL_JP
                // Snapshot once — a session lasts seconds, so it isn't worth reacting to a
                // threshold change made mid-utterance the way WakeWordService does.
                val micThreshold = settingsRepository.currentMicInputThreshold()

                // Every failure path below just reports and returns — the finally block is what
                // clears isPreparing/isListening, so no flag bookkeeping in between. The error is
                // only delivered if this session is still current (see generation's comment) —
                // some of these checks take seconds, and a session stopped in the meantime must
                // not surface its error into whatever session runs next.
                if (!speechModelManager.isModelAvailable(speechModel)) {
                    launch(Dispatchers.Main) { if (sessionGeneration == generation) onError(context.getString(com.suzuri.lmdroid.R.string.chat_voice_input_error_model)) }
                    return@launch
                }

                val engine = when (speechModel.engineType) {
                    SpeechEngineType.VOSK -> {
                        val model = voskRepository.getModel(speechModel) ?: run {
                            launch(Dispatchers.Main) { if (sessionGeneration == generation) onError(context.getString(com.suzuri.lmdroid.R.string.chat_voice_input_error_model)) }
                            return@launch
                        }
                        VoskEngine(model)
                    }
                    SpeechEngineType.WHISPER -> {
                        val modelFile = speechModelManager.getModelDir(speechModel)
                        if (!modelFile.exists()) {
                            launch(Dispatchers.Main) { if (sessionGeneration == generation) onError(context.getString(com.suzuri.lmdroid.R.string.chat_voice_input_error_model)) }
                            return@launch
                        }
                        WhisperEngine(modelFile.absolutePath, settingsRepository.currentSelectedSttLanguage())
                    }
                }

                if (!engine.isReady) {
                    launch(Dispatchers.Main) { if (sessionGeneration == generation) onError(context.getString(com.suzuri.lmdroid.R.string.chat_voice_input_error)) }
                    return@launch
                }
                
                // TEMP DIAGNOSTIC LOGGING — remove once the Bluetooth beep/mic-routing issue is fixed.
                Log.d("LocalVoiceInput", "DIAG input devices: " + audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .joinToString { "type=${it.type} name=${it.productName}" })
                Log.d("LocalVoiceInput", "DIAG output devices: " + audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .joinToString { "type=${it.type} name=${it.productName}" })

                // Route to a Bluetooth headset mic only while one is actually connected.
                // Starting SCO unconditionally leaves the built-in mic silent on devices
                // (e.g. the emulator) that report SCO as available without a headset.
                val bluetoothScoDevice = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .find { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                val btScoConnected = bluetoothScoDevice != null
                Log.d("LocalVoiceInput", "DIAG btScoConnected=$btScoConnected")
                var usingBluetoothCommunicationDevice = false
                if (bluetoothScoDevice != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // setCommunicationDevice (API 31+) is the modern replacement for the
                        // legacy startBluetoothSco()/isBluetoothScoOn pair below, and — unlike
                        // it — actually reports success/failure synchronously. The legacy path's
                        // ACTION_SCO_AUDIO_STATE_UPDATED broadcast has proven unreliable on at
                        // least one real device (Samsung/One UI): it never fired, silently
                        // leaving capture on the phone's own built-in mic instead of the headset.
                        usingBluetoothCommunicationDevice = audioManager.setCommunicationDevice(bluetoothScoDevice)
                        Log.d("LocalVoiceInput", "DIAG setCommunicationDevice result=$usingBluetoothCommunicationDevice")
                    } else {
                        Log.d("LocalVoiceInput", "Starting Bluetooth SCO for UI listening")
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                        // The SCO link takes up to ~2s to actually come up — without waiting for
                        // it, both the start-listening beep below and the first captured audio
                        // can be dropped or silently routed to the wrong device because the link
                        // isn't ready yet. Best-effort: if it never connects, fall through anyway.
                        usingBluetoothCommunicationDevice = awaitBluetoothScoConnected(context)
                        Log.d("LocalVoiceInput", "DIAG scoConnectedInTime=$usingBluetoothCommunicationDevice isBluetoothScoOn=${audioManager.isBluetoothScoOn}")
                    }
                }

                val bufferSize = 8000
                val minBufSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    launch(Dispatchers.Main) { if (sessionGeneration == generation) onError(context.getString(com.suzuri.lmdroid.R.string.chat_voice_input_permission_denied)) }
                    return@launch
                }

                // AudioSource.VOICE_RECOGNITION is deliberately "raw" (no AGC/NS/AEC) for best
                // recognition quality, but on-device testing showed it never actually routes to a
                // Bluetooth SCO mic — audio stayed on the phone's built-in mic even with
                // setCommunicationDevice()/preferredDevice both pointed at the headset. SCO
                // routing in practice is tied to sources meant for call-style communication, so
                // once a Bluetooth communication device is active, switch to
                // VOICE_COMMUNICATION — that does add call-oriented processing, but it's the
                // source that actually reaches the headset.
                val audioSource = if (usingBluetoothCommunicationDevice) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
                Log.d("LocalVoiceInput", "DIAG audioSource=$audioSource (VOICE_RECOGNITION=${MediaRecorder.AudioSource.VOICE_RECOGNITION} VOICE_COMMUNICATION=${MediaRecorder.AudioSource.VOICE_COMMUNICATION})")
                val recorder = AudioRecord(audioSource, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize.coerceAtLeast(bufferSize))
                // Belt-and-suspenders: explicitly pin capture to the Bluetooth mic rather than
                // relying solely on the communication-device/SCO state to steer default routing.
                if (usingBluetoothCommunicationDevice && bluetoothScoDevice != null) {
                    recorder.preferredDevice = bluetoothScoDevice
                }

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    launch(Dispatchers.Main) { if (sessionGeneration == generation) onError(context.getString(com.suzuri.lmdroid.R.string.chat_voice_input_error_mic)) }
                    return@launch
                }

                // Cue the user that it's safe to speak now — unlike the system recognizer
                // (Google's, on most devices), which plays its own start tone, on-device
                // recognition otherwise gives no signal that listening actually began. The mic
                // isn't recording yet at this point (recorder.startRecording() below), so the
                // beep itself can't leak into the captured audio.
                //
                // Stream choice matters for Bluetooth: by default Android only routes
                // STREAM_MUSIC (and STREAM_VOICE_CALL while SCO is active) to a connected
                // Bluetooth headset — STREAM_NOTIFICATION/STREAM_RING/STREAM_SYSTEM stay on the
                // phone's own speaker regardless of what's connected. The system recognizer's own
                // start tone plays over STREAM_MUSIC for this reason, so this mirrors it: without
                // an active SCO/communication link, use STREAM_MUSIC; once that link is actually
                // up, only STREAM_VOICE_CALL reaches the headset over it.
                try {
                    val toneStreamType = if (usingBluetoothCommunicationDevice) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
                    Log.d("LocalVoiceInput", "DIAG toneStreamType=$toneStreamType (STREAM_VOICE_CALL=${AudioManager.STREAM_VOICE_CALL} STREAM_MUSIC=${AudioManager.STREAM_MUSIC})")
                    val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(if (usingBluetoothCommunicationDevice) AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        .build()
                    val focusResult = audioManager.requestAudioFocus(focusRequest)
                    Log.d("LocalVoiceInput", "DIAG requestAudioFocus result=$focusResult (GRANTED=${AudioManager.AUDIOFOCUS_REQUEST_GRANTED})")

                    val toneGenerator = ToneGenerator(toneStreamType, 80)
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, START_LISTENING_BEEP_DURATION_MS)
                    delay(START_LISTENING_BEEP_DURATION_MS.toLong())
                    toneGenerator.release()
                    audioManager.abandonAudioFocusRequest(focusRequest)
                } catch (e: RuntimeException) {
                    // ToneGenerator can fail to allocate on some devices/emulators — not worth aborting listening for.
                    Log.w("LocalVoiceInput", "Failed to play start-listening beep", e)
                }

                if (sessionGeneration != generation) return@launch

                recorder.startRecording()
                Log.d("LocalVoiceInput", "DIAG recorder started, preferredDevice=${recorder.preferredDevice?.let { "type=${it.type} name=${it.productName}" }}")
                // Only now — mic actually capturing — is it honest to prompt the user to speak.
                // Set synchronously (not via Dispatchers.Main): the capture loop below checks
                // isListening on this thread and would exit at once if it had to wait for Main.
                isPreparing = false
                isListening = true
                val buffer = ShortArray(bufferSize)

                try {
                    // Gates out leading silence/noise below micThreshold so ambient sound alone
                    // can't feed the engine — once real speech crosses the threshold once, every
                    // subsequent buffer (silence included) is fed through as before, so the
                    // engine's own end-of-utterance/finalization detection is untouched.
                    var speechStarted = false
                    var routedDeviceLogged = false
                    // The generation check covers a session stopped while still preparing (model
                    // loading takes seconds): this coroutine then flips isListening back on after
                    // stop() cleared it, and without this the mic would keep capturing for a
                    // session nobody is looking at anymore.
                    while (isListening && sessionGeneration == generation) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            if (!routedDeviceLogged) {
                                routedDeviceLogged = true
                                Log.d("LocalVoiceInput", "DIAG first read: routedDevice=${recorder.routedDevice?.let { "type=${it.type} name=${it.productName}" }} peak=${normalizedPeakLevel(buffer, read)}")
                            }
                            if (!speechStarted) {
                                speechStarted = normalizedPeakLevel(buffer, read) >= micThreshold
                                if (!speechStarted) continue
                            }
                            if (engine.acceptAudio(buffer, read)) {
                                val result = engine.getResult()
                                launch(Dispatchers.Main) {
                                    // Stop on any final result (including empty) — even one
                                    // belonging to a session that was stopped/replaced in flight.
                                    isListening = false
                                    isFinalizing = false
                                    if (sessionGeneration != generation) return@launch
                                    // Delivered even when blank: the caller shows nothing and
                                    // drops any stale partial transcript (a blank final means
                                    // the session ended without usable speech).
                                    onResult(result.trim())
                                }
                            } else {
                                val partial = engine.getPartialResult()
                                if (partial.isNotBlank()) {
                                    launch(Dispatchers.Main) {
                                        if (sessionGeneration == generation) onPartialResult(partial)
                                    }
                                }
                            }
                            // Mirror the engine's finalization phase for the UI ("recognizing…").
                            val engineFinalizing = engine.isFinalizing
                            if (engineFinalizing != isFinalizing) isFinalizing = engineFinalizing
                        }
                    }
                } finally {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (usingBluetoothCommunicationDevice) {
                            audioManager.clearCommunicationDevice()
                        }
                    } else if (audioManager.isBluetoothScoOn) {
                        Log.d("LocalVoiceInput", "Stopping Bluetooth SCO")
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                    }
                    recorder.stop()
                    recorder.release()
                    engine.release()
                }
            } catch (e: CancellationException) {
                // Session was stopped or the composition went away — not an error worth showing.
                throw e
            } catch (e: Throwable) {
                Log.e("LocalVoiceInput", "Recognition error", e)
                launch(Dispatchers.Main) {
                    if (sessionGeneration != generation) return@launch
                    val errorMsg = if (e is UnsatisfiedLinkError) {
                        "Native library failed to load. Please try restarting the app."
                    } else {
                        context.getString(com.suzuri.lmdroid.R.string.chat_voice_input_error)
                    }
                    onError(errorMsg)
                }
            } finally {
                launch(Dispatchers.Main) {
                    isListening = false
                    isPreparing = false
                    isFinalizing = false
                }
            }
        }
    }

    fun stop() {
        // Invalidate this session's not-yet-delivered callbacks too (see generation's comment).
        generation++
        isListening = false
        isPreparing = false
        isFinalizing = false
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

/**
 * Common shape mic-button callers need, regardless of which engine backs it — see
 * [rememberComposerVoiceInputState]. [isPreparing]/[isFinalizing] are always false for the system
 * recognizer ([VoiceInputState]), which has no equivalent wind-up/finalization phase.
 */
interface ComposerVoiceInputState {
    val isListening: Boolean
    val isPreparing: Boolean get() = false
    val isFinalizing: Boolean get() = false
    val isAvailable: Boolean
    fun start(scope: CoroutineScope)
    fun stop()
}

/**
 * Picks between [VoiceInputState] (the OS's default [SpeechRecognizer] service — Google's cloud
 * recognizer on most devices) and [LocalVoiceInputState] (on-device Vosk/Whisper), based on
 * [useLocal] (see SettingsRepository.voiceInputUseLocalEngine), behind the single interface a mic
 * button needs. Both underlying `remember*VoiceInputState` calls already manage their own
 * lifecycle (recognizer creation/teardown), so switching [useLocal] tears down the previous
 * engine and spins up the other one.
 */
@Composable
fun rememberComposerVoiceInputState(
    useLocal: Boolean,
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
    onPartialResult: (String) -> Unit = {},
): ComposerVoiceInputState {
    return if (useLocal) {
        val local = rememberLocalVoiceInputState(onResult = onResult, onError = onError, onPartialResult = onPartialResult)
        remember(local) {
            object : ComposerVoiceInputState {
                override val isListening get() = local.isListening
                override val isPreparing get() = local.isPreparing
                override val isFinalizing get() = local.isFinalizing
                override val isAvailable get() = local.isAvailable
                override fun start(scope: CoroutineScope) = local.start(scope)
                override fun stop() = local.stop()
            }
        }
    } else {
        val system = rememberVoiceInputState(onResult = onResult, onError = onError, onPartialResult = onPartialResult)
        remember(system) {
            object : ComposerVoiceInputState {
                override val isListening get() = system.isListening
                override val isAvailable get() = system.isAvailable
                override fun start(scope: CoroutineScope) = system.start()
                override fun stop() = system.stop()
            }
        }
    }
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
