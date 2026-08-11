package com.suzuri.lmdroid.data.stt

import kotlinx.serialization.Serializable

@Serializable
enum class SpeechEngineType {
    VOSK,
    WHISPER
}

@Serializable
data class SpeechModel(
    val id: String,
    val name: String,
    val engineType: SpeechEngineType,
    val url: String?,
    val sizeBytes: Long,
    val isBundled: Boolean = false,
    val assetPath: String? = null
) {
    companion object {
        val VOSK_SMALL_JP = SpeechModel(
            id = "vosk-jp-small",
            name = "Vosk Small (JP)",
            engineType = SpeechEngineType.VOSK,
            url = null,
            sizeBytes = 46 * 1024 * 1024,
            isBundled = true,
            assetPath = "model"
        )

        val VOSK_LARGE_JP = SpeechModel(
            id = "vosk-jp-large",
            name = "Vosk Large (JP)",
            engineType = SpeechEngineType.VOSK,
            url = "https://alphacephei.com/vosk/models/vosk-model-ja-0.22.zip",
            sizeBytes = 1000L * 1024 * 1024
        )

        val WHISPER_TINY = SpeechModel(
            id = "whisper-tiny",
            name = "Whisper Tiny",
            engineType = SpeechEngineType.WHISPER,
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
            sizeBytes = 75 * 1024 * 1024
        )

        val WHISPER_BASE = SpeechModel(
            id = "whisper-base",
            name = "Whisper Base",
            engineType = SpeechEngineType.WHISPER,
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
            sizeBytes = 142 * 1024 * 1024
        )
        
        val WHISPER_SMALL = SpeechModel(
            id = "whisper-small",
            name = "Whisper Small",
            engineType = SpeechEngineType.WHISPER,
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
            sizeBytes = 465 * 1024 * 1024
        )

        val ALL_MODELS = listOf(VOSK_SMALL_JP, VOSK_LARGE_JP, WHISPER_TINY, WHISPER_BASE, WHISPER_SMALL)
    }
}
