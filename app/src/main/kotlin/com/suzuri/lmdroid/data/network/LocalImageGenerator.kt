package com.suzuri.lmdroid.data.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * On-device local image generation.
 * This is a placeholder for a real implementation using a library like MediaPipe Image Generator
 * or stable-diffusion.cpp via JNI.
 */
class LocalImageGenerator(
    private val context: Context,
) : ImageGenerator {
    override fun generate(
        params: ImageGenerationParams,
        apiKey: String?,
        baseUrl: String?
    ): Flow<ImageGenerationState> = flow {
        emit(ImageGenerationState.Loading(message = "ローカル生成を開始中..."))

        // TODO: Implement on-device generation logic here.
        // For MediaPipe, you would initialize the ImageGenerator task and call generate().
        
        emit(ImageGenerationState.Error("ローカル生成は現在未実装です。MediaPipe等のライブラリのセットアップが必要です。"))
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "LocalImageGenerator"
    }
}
