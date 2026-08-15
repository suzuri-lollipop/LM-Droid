package com.suzuri.lmdroid.data.stt

/**
 * Common interface for different speech recognition backends.
 */
interface SpeechRecognizerEngine {
    val isReady: Boolean

    /**
     * True while the engine has detected the end of the utterance and is running its final
     * inference — no more audio is being accepted, but the result isn't ready yet. Lets the UI
     * swap "speak now" for "recognizing…" instead of looking stuck (Whisper's final pass takes
     * seconds on-device). Engines without a separate finalization phase stay false.
     */
    val isFinalizing: Boolean get() = false

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
