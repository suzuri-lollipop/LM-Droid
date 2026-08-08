package com.suzuri.lmdroid.data.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.ByteArrayOutputStream

/**
 * On-device local image generation using stable-diffusion.cpp via JNI.
 */
class LocalImageGenerator(
    private val context: android.content.Context,
) : ImageGenerator {
    private val native = StableDiffusionNative()
    private var modelContext: Long = 0
    private var currentModelPath: String? = null

    override fun generate(
        params: ImageGenerationParams,
        apiKey: String?,
        baseUrl: String?
    ): Flow<ImageGenerationState> = flow {
        if (baseUrl == null) {
            emit(ImageGenerationState.Error("モデルファイルが指定されていません。"))
            return@flow
        }

        // Load model if path changed
        if (modelContext == 0L || currentModelPath != baseUrl) {
            emit(ImageGenerationState.Loading(message = "モデルをロード中: ${baseUrl}..."))
            if (modelContext != 0L) {
                native.freeModel(modelContext)
            }
            modelContext = native.loadModel(baseUrl)
            currentModelPath = baseUrl
            
            if (modelContext == 0L) {
                emit(ImageGenerationState.Error("モデルのロードに失敗しました。パスを確認してください。"))
                return@flow
            }
        }

        val modelName = baseUrl.substringAfterLast("/")
        emit(ImageGenerationState.Loading(message = "画像を生成中 (${modelName})..."))

        val baseBitmap = params.baseImage?.let { base64 ->
            val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        }

        val result = native.generateImage(
            context = modelContext,
            prompt = params.prompt,
            negativePrompt = params.negativePrompt.orEmpty(),
            width = params.width,
            height = params.height,
            seed = params.seed,
            sampleSteps = 20, // Default steps
            baseImage = baseBitmap,
            denoisingStrength = params.denoisingStrength,
            onProgress = { progress ->
                // Progress update can be emitted here if UI supports it
            }
        )

        if (result != null) {
            val stream = ByteArrayOutputStream()
            result.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val bytes = stream.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            emit(ImageGenerationState.Success(listOf("data:image/png;base64,$base64")))
        } else {
            emit(ImageGenerationState.Error("画像の生成に失敗しました。コア実装（sd_jni.cpp）のプレースホルダーを確認してください。"))
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "LocalImageGenerator"
    }
}
