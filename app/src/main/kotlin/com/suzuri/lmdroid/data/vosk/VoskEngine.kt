package com.suzuri.lmdroid.data.vosk

import com.suzuri.lmdroid.data.stt.SpeechRecognizerEngine
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

class VoskEngine(
    model: Model,
    sampleRate: Float = 16000.0f
) : SpeechRecognizerEngine {
    private val recognizer = Recognizer(model, sampleRate)
    private var lastResult = ""
    private var lastPartial = ""

    override val isReady: Boolean = true

    override fun acceptAudio(data: ShortArray, length: Int): Boolean {
        return if (recognizer.acceptWaveForm(data, length)) {
            val json = recognizer.result
            lastResult = JSONObject(json).optString("text", "")
            true
        } else {
            val json = recognizer.partialResult
            lastPartial = JSONObject(json).optString("partial", "")
            false
        }
    }

    override fun getResult(): String = lastResult

    override fun getPartialResult(): String = lastPartial

    override fun reset() {
        recognizer.reset()
        lastResult = ""
        lastPartial = ""
    }

    override fun release() {
        recognizer.close()
    }
}
