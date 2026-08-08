package com.suzuri.lmdroid.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Basic ComfyUI API integration.
 * Currently only submits the prompt. Full polling/WebSocket support can be added later.
 */
class ComfyUiGenerator(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) : ImageGenerator {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override fun generate(
        params: ImageGenerationParams,
        apiKey: String?,
        baseUrl: String?
    ): Flow<ImageGenerationState> = flow {
        emit(ImageGenerationState.Loading(message = "ComfyUIに送信中..."))

        val url = if (baseUrl.isNullOrBlank()) {
            emit(ImageGenerationState.Error("Base URLが設定されていません"))
            return@flow
        } else {
            "${baseUrl.trimEnd('/')}/prompt"
        }

        // Note: ComfyUI requires a full workflow JSON in the 'prompt' field.
        // For a generic 'base part', we might need a way to construct a basic workflow
        // or expect the prompt to be provided in a specific way.
        // Here we'll wrap the prompt text in a very basic 'CLIPTextEncode' style structure
        // if it's not already a JSON workflow, but that's highly dependent on the user's setup.
        // For now, we'll just send it as a simple 'prompt' object if possible.
        
        // This is a placeholder for a real workflow construction logic.
        val workflow = JsonObject(mapOf(
            "6" to JsonObject(mapOf(
                "class_type" to JsonPrimitive("CLIPTextEncode"),
                "inputs" to JsonObject(mapOf("text" to JsonPrimitive(params.prompt)))
            ))
            // ... more nodes needed for a real working ComfyUI workflow
        ))

        val requestBodyJson = json.encodeToString(
            ComfyPromptRequest.serializer(),
            ComfyPromptRequest(prompt = workflow)
        )

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
                    Log.w(TAG, "ComfyUI API error: ${response.code} $errorBody")
                    emit(ImageGenerationState.Error("APIエラー: ${response.code}"))
                    return@flow
                }

                val bodyString = response.body?.string() ?: ""
                val comfyResponse = json.decodeFromString(ComfyResponse.serializer(), bodyString)
                
                emit(ImageGenerationState.Loading(message = "タスク受理: ${comfyResponse.promptId}"))
                // In a real implementation, we would poll /history/{promptId} or use WebSockets here.
                // For the 'base part', we just indicate success with the ID for now.
                emit(ImageGenerationState.Success(listOf("comfyui_prompt_id:${comfyResponse.promptId}")))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error calling ComfyUI API", e)
            emit(ImageGenerationState.Error("ネットワークエラー: ${e.message}"))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error calling ComfyUI API", e)
            emit(ImageGenerationState.Error("予期せぬエラー: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "ComfyUiGenerator"
    }
}
