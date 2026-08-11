package com.suzuri.lmdroid.data.stt

import android.util.Log
import kotlin.math.sqrt

class WhisperEngine(private val modelPath: String) : SpeechRecognizerEngine {
    private val native = WhisperNative()
    private var context: Long = 0L
    private var result: String = ""
    
    // Audio accumulation buffer
    private var audioBuffer = mutableListOf<Float>()
    
    // VAD state
    private var isSpeaking = false
    private var silenceChunks = 0
    private val silenceThreshold = 10 // approx 5 seconds (0.5s per chunk * 10)
    private val minAudioForInference = 16000 * 1 // at least 1 second of audio
    private val energyThreshold = 0.01f // Lowered RMS threshold for better sensitivity

    override val isReady: Boolean
        get() = context != 0L

    init {
        try {
            Log.d(TAG, "Loading model from $modelPath")
            context = native.init(modelPath)
            if (context == 0L) {
                Log.e(TAG, "Failed to init whisper context")
            } else {
                Log.d(TAG, "Whisper initialized successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Whisper", e)
        }
    }

    override fun acceptAudio(data: ShortArray, length: Int): Boolean {
        if (context == 0L) return false

        // Calculate energy (RMS)
        var sum = 0.0
        for (i in 0 until length) {
            val sample = data[i] / 32768.0f
            sum += (sample * sample).toDouble()
            audioBuffer.add(sample)
        }
        val rms = sqrt(sum / length).toFloat()
        
        if (rms > energyThreshold) {
            isSpeaking = true
            silenceChunks = 0
        } else if (isSpeaking) {
            silenceChunks++
        }

        // Trigger inference if we have enough silence after speaking, or buffer gets too long
        val shouldTrigger = (isSpeaking && silenceChunks >= 2) || audioBuffer.size >= 16000 * 20 // 20s max

        if (shouldTrigger && audioBuffer.size >= minAudioForInference) {
            Log.d(TAG, "Triggering inference, buffer size: ${audioBuffer.size}")
            val ret = native.full(context, audioBuffer.toFloatArray())
            if (ret == 0) {
                val nSegments = native.getNSegments(context)
                val sb = StringBuilder()
                for (i in 0 until nSegments) {
                    sb.append(native.getSegmentText(context, i))
                }
                result = sb.toString().trim()
                Log.d(TAG, "Result: $result")
                
                // Clear state for next sentence
                resetState()
                return result.isNotEmpty()
            }
            // If inference failed, we might want to keep the buffer or clear it
            // For now, let's keep it but increment silence to eventually clear or retry
        } else if (!isSpeaking && audioBuffer.size > 16000 * 5) {
            // Clear buffer if it's just long silence
            audioBuffer.clear()
        }

        return false
    }

    override fun getResult(): String = result

    override fun getPartialResult(): String = ""

    override fun reset() {
        result = ""
        resetState()
    }

    private fun resetState() {
        audioBuffer.clear()
        isSpeaking = false
        silenceChunks = 0
    }

    override fun release() {
        if (context != 0L) {
            native.free(context)
            context = 0L
        }
    }
    
    companion object {
        private const val TAG = "WhisperEngine"
    }
}
