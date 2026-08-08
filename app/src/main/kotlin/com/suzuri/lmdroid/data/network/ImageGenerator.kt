package com.suzuri.lmdroid.data.network

import kotlinx.coroutines.flow.Flow

sealed class ImageGenerationState {
    object Idle : ImageGenerationState()
    data class Loading(val progress: Float? = null, val message: String? = null) : ImageGenerationState()
    data class Success(val imageUrls: List<String>) : ImageGenerationState()
    data class Error(val message: String) : ImageGenerationState()
}

data class ImageGenerationParams(
    val prompt: String,
    val negativePrompt: String? = null,
    val width: Int = 512,
    val height: Int = 512,
    val numImages: Int = 1,
    val seed: Long = -1,
    val baseImage: String? = null, // For img2img, typically base64
    val denoisingStrength: Float = 0.75f,
)

interface ImageGenerator {
    fun generate(params: ImageGenerationParams, apiKey: String?, baseUrl: String?): Flow<ImageGenerationState>
}
