package com.suzuri.lmdroid.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class StableDiffusionGenerator(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) : ImageGenerator {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override fun generate(
        params: ImageGenerationParams,
        apiKey: String?,
        baseUrl: String?
    ): Flow<ImageGenerationState> = flow {
        emit(ImageGenerationState.Loading(message = "生成中..."))

        val url = if (baseUrl.isNullOrBlank()) {
            emit(ImageGenerationState.Error("Base URLが設定されていません"))
            return@flow
        } else {
            val endpoint = if (params.baseImage != null) "img2img" else "txt2img"
            "${baseUrl.trimEnd('/')}/sdapi/v1/$endpoint"
        }

        val requestBodyJson = if (params.baseImage != null) {
            json.encodeToString(
                SdImg2ImgRequest.serializer(),
                SdImg2ImgRequest(
                    initImages = listOf(params.baseImage),
                    prompt = params.prompt,
                    negativePrompt = params.negativePrompt,
                    width = params.width,
                    height = params.height,
                    denoisingStrength = params.denoisingStrength,
                    seed = params.seed
                )
            )
        } else {
            json.encodeToString(
                SdTxt2ImgRequest.serializer(),
                SdTxt2ImgRequest(
                    prompt = params.prompt,
                    negativePrompt = params.negativePrompt,
                    width = params.width,
                    height = params.height,
                    seed = params.seed
                )
            )
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBodyJson.toRequestBody(jsonMediaType))

        if (!apiKey.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        val request = requestBuilder.build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.w(TAG, "SD API error: ${response.code} $errorBody")
                    emit(ImageGenerationState.Error("APIエラー: ${response.code}"))
                    return@flow
                }

                val bodyString = response.body?.string() ?: ""
                val sdResponse = json.decodeFromString(SdResponse.serializer(), bodyString)
                
                // SD WebUI returns base64 encoded images
                // We might want to save them to files later, but for now we return them as is
                // or prefixed with data:image/png;base64,
                val imageUrls = sdResponse.images.map { 
                    if (it.startsWith("data:")) it else "data:image/png;base64,$it"
                }
                emit(ImageGenerationState.Success(imageUrls))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error calling SD API", e)
            emit(ImageGenerationState.Error("ネットワークエラー: ${e.message}"))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error calling SD API", e)
            emit(ImageGenerationState.Error("予期せぬエラー: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "StableDiffusionGenerator"
    }
}
