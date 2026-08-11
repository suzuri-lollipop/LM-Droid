package com.suzuri.lmdroid.data.stt

/**
 * Common interface for different speech recognition backends.
 */
interface SpeechRecognizerEngine {
    val isReady: Boolean

    /**
     * Feeds raw PCM audio data (16kHz, mono, 16-bit) to the engine.
     * @return true if a final result was produced.
     */
    fun acceptAudio(data: ShortArray, length: Int): Boolean

    /**
     * Gets the current final result.
     */
    fun getResult(): String

    /**
     * Gets the current partial result.
     */
    fun getPartialResult(): String

    /**
     * Resets the recognizer state for a new sentence.
     */
    fun reset()

    /**
     * Releases resources.
     */
    fun release()
}
