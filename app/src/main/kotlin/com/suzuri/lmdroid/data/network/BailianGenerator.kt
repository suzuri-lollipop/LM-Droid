package com.suzuri.lmdroid.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class BailianGenerator(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) : ImageGenerator {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override fun generate(
        params: ImageGenerationParams,
        apiKey: String?,
        baseUrl: String?
    ): Flow<ImageGenerationState> = flow {
        if (apiKey.isNullOrBlank()) {
            emit(ImageGenerationState.Error("APIキーが設定されていません"))
            return@flow
        }

        emit(ImageGenerationState.Loading(message = "タスクを送信中..."))

        val submitUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis"
        
        val requestBody = BailianImageRequest(
            model = "wanx-v1", // Default model
            input = BailianImageInput(
                prompt = params.prompt,
                refImg = params.baseImage
            ),
            parameters = BailianImageParameters(
                n = params.numImages,
                size = "${params.width}*${params.height}"
            )
        )

        val request = Request.Builder()
            .url(submitUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("X-DashScope-Async", "enable")
            .post(json.encodeToString(BailianImageRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()

        val taskId = try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.w(TAG, "Bailian submit error: ${response.code} $errorBody")
                    emit(ImageGenerationState.Error("送信失敗: ${response.code}"))
                    return@flow
                }
                val bodyString = response.body?.string() ?: ""
                val res = json.decodeFromString(BailianImageResponse.serializer(), bodyString)
                res.output.taskId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error submitting Bailian task", e)
            emit(ImageGenerationState.Error("エラー: ${e.message}"))
            return@flow
        }

        // Polling
        var status = "PENDING"
        var attempts = 0
        while (status == "PENDING" || status == "RUNNING") {
            if (attempts > 30) { // Timeout after ~1 minute
                emit(ImageGenerationState.Error("タイムアウトしました"))
                return@flow
            }
            delay(2000)
            attempts++
            
            val pollRequest = Request.Builder()
                .url("https://dashscope.aliyuncs.com/api/v1/tasks/$taskId")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            try {
                okHttpClient.newCall(pollRequest).execute().use { response ->
                    val bodyString = response.body?.string() ?: ""
                    val res = json.decodeFromString(BailianTaskResponse.serializer(), bodyString)
                    status = res.output.taskStatus
                    
                    emit(ImageGenerationState.Loading(message = "処理中... ($status)"))

                    if (status == "SUCCEEDED") {
                        val urls = res.output.results?.map { it.url } ?: emptyList()
                        emit(ImageGenerationState.Success(urls))
                        return@flow
                    } else if (status == "FAILED" || status == "UNKNOWN") {
                        emit(ImageGenerationState.Error("生成失敗: $status"))
                        return@flow
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error polling Bailian task", e)
                // Continue polling on transient errors?
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "BailianGenerator"
    }
}
