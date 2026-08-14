package com.suzuri.lmdroid.data.stt

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

class WhisperEngine(private val modelPath: String) : SpeechRecognizerEngine {
    private val native = WhisperNative()
    private val context: Long
    @Volatile private var result: String = ""
    @Volatile private var partialResult: String = ""

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var inferenceJob: Job? = null

    // Audio accumulation buffer
    private val audioBuffer = mutableListOf<Float>()

    // VAD state
    private var isSpeaking = false
    private var silenceChunks = 0
    private var framesProcessed = 0
    private var samplesSinceLastInference = 0
    @Volatile private var isFinalizing = false

    private val silenceThreshold = 6 // approx 3 seconds (0.5s per chunk * 6)
    private val minAudioForInference = 16000 * 1 // at least 1 second of audio
    private val energyThreshold = 0.005f // Lowered RMS threshold for better sensitivity
    private val noSpeechTimeoutFrames = 10 // approx 5 seconds
    private val partialInferenceIntervalSamples = 16000 * 1.5 // 1.5 seconds
    // Partials re-run whisper_full over the snapshot, so bound their cost to recent audio.
    private val maxPartialInferenceSamples = 16000 * 10

    override val isReady: Boolean
        get() = context != 0L

    init {
        context = try {
            Log.d(TAG, "Acquiring whisper context for $modelPath")
            acquireContext(native, modelPath).also {
                if (it == 0L) {
                    Log.e(TAG, "Failed to init whisper context")
                } else {
                    Log.d(TAG, "Whisper initialized successfully")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error initializing Whisper", e)
            0L
        }
    }

    override fun acceptAudio(data: ShortArray, length: Int): Boolean {
        if (context == 0L) return false
        
        // If we are already finalizing, just check if the result is ready
        if (isFinalizing) {
            return if (result.isNotEmpty()) {
                Log.d(TAG, "Final result ready, signaling completion")
                true
            } else {
                false
            }
        }

        framesProcessed++

        // Calculate energy (RMS)
        var sum = 0.0
        val samples = FloatArray(length)
        for (i in 0 until length) {
            val sample = data[i] / 32768.0f
            sum += (sample * sample).toDouble()
            samples[i] = sample
        }
        val rms = sqrt(sum / length).toFloat()
        
        synchronized(audioBuffer) {
            for (s in samples) audioBuffer.add(s)
            samplesSinceLastInference += length
        }

        if (rms > energyThreshold) {
            isSpeaking = true
            silenceChunks = 0
        } else if (isSpeaking) {
            silenceChunks++
        }

        // Check if we should run partial inference
        if (isSpeaking && !isFinalizing && samplesSinceLastInference >= partialInferenceIntervalSamples) {
            runInference(isFinal = false)
            samplesSinceLastInference = 0
        }

        // Timeout if no speech detected for a while
        if (!isSpeaking && framesProcessed >= noSpeechTimeoutFrames) {
            Log.d(TAG, "No speech detected timeout")
            result = ""
            return true
        }

        // Trigger final inference if we have enough silence after speaking, or buffer gets too long
        val shouldTriggerFinal = (isSpeaking && silenceChunks >= 2) || (audioBuffer.size >= 16000 * 25)

        if (shouldTriggerFinal && audioBuffer.size >= minAudioForInference) {
            Log.d(TAG, "Triggering final inference, buffer size: ${audioBuffer.size}")
            isFinalizing = true
            partialResult = "Processing..."
            runInference(isFinal = true)
        } else if (!isSpeaking && audioBuffer.size > 16000 * 5) {
            // Clear buffer if it's just long silence
            synchronized(audioBuffer) {
                audioBuffer.clear()
            }
        }

        // If we were finalizing and the job is done, we return true
        if (isFinalizing && result.isNotEmpty()) {
            return true
        }

        return false
    }

    private fun runInference(isFinal: Boolean) {
        // Cancel previous partial job if it's still running
        if (!isFinal && inferenceJob?.isActive == true) {
            return // Don't queue up partials if one is already running
        }

        inferenceJob = scope.launch {
            val startTime = System.currentTimeMillis()
            val audioCopy = synchronized(audioBuffer) {
                if (isFinal || audioBuffer.size <= maxPartialInferenceSamples) {
                    audioBuffer.toFloatArray()
                } else {
                    audioBuffer.subList(audioBuffer.size - maxPartialInferenceSamples, audioBuffer.size).toFloatArray()
                }
            }

            inferenceLock.withLock {
                val ret = native.full(context, audioCopy)
                val duration = System.currentTimeMillis() - startTime
                if (ret == 0) {
                    val nSegments = native.getNSegments(context)
                    val sb = StringBuilder()
                    for (i in 0 until nSegments) {
                        // Whisper hallucinates ("thank you for watching", "[Music]", ...)
                        // on noise and silence; drop segments it flags as non-speech.
                        if (native.getSegmentNoSpeechProb(context, i) < 0.6f) {
                            sb.append(native.getSegmentText(context, i))
                        }
                    }
                    val text = sb.toString().trim()
                    
                    if (isFinal) {
                        result = if (text.isEmpty()) " " else text // Ensure not empty to signal completion
                        Log.d(TAG, "Final inference took ${duration}ms. Result: $result")
                    } else {
                        partialResult = text
                        Log.d(TAG, "Partial inference took ${duration}ms. Result: $partialResult")
                    }
                } else {
                    Log.e(TAG, "Whisper inference failed with code: $ret after ${duration}ms")
                    if (isFinal) result = " " // Signal failure/empty
                }
            }
        }
    }

    override fun getResult(): String = result.trim()

    override fun getPartialResult(): String = partialResult

    override fun reset() {
        result = ""
        resetState()
    }

    private fun resetState() {
        synchronized(audioBuffer) {
            audioBuffer.clear()
        }
        isSpeaking = false
        silenceChunks = 0
        framesProcessed = 0
        samplesSinceLastInference = 0
        isFinalizing = false
        partialResult = ""
        result = ""
    }

    override fun release() {
        inferenceJob?.cancel()
    }

    companion object {
        private const val TAG = "WhisperEngine"
        private val initLock = Any()
        // Shared so concurrent engines can never run whisper_full on one context at once.
        private val inferenceLock = Mutex()
        private var cachedModelPath: String? = null
        private var cachedContext: Long = 0L

        // Loading a Whisper model takes seconds and hundreds of MB of parsing; keep one
        // context alive across listening sessions, mirroring VoskRepository's caching.
        private fun acquireContext(native: WhisperNative, modelPath: String): Long = synchronized(initLock) {
            if (cachedContext != 0L && cachedModelPath != modelPath) {
                native.free(cachedContext)
                cachedContext = 0L
                cachedModelPath = null
            }
            if (cachedContext == 0L) {
                cachedContext = native.init(modelPath)
                cachedModelPath = modelPath
            }
            cachedContext
        }
    }
}
