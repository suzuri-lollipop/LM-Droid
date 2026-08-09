package com.suzuri.lmdroid.data.tts

import android.util.Log
import com.suzuri.lmdroid.data.network.OpenAiApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Client for OpenAI's Text-to-Speech API (/v1/audio/speech).
 * Returns the raw audio bytes (typically MP3).
 */
class OpenAiTtsClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun synthesize(
        apiKey: String,
        baseUrl: String,
        model: String,
        voice: String,
        text: String,
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        val requestJson = json.encodeToString(
            SpeechRequest.serializer(),
            SpeechRequest(model = model, voice = voice, input = text)
        )
        val requestBody = requestJson.toRequestBody(jsonMediaType)

        val url = "${OpenAiApiClient.normalizeBaseUrl(baseUrl)}/audio/speech"
        val request = try {
            Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null) {
                        Result.success(bytes)
                    } else {
                        Result.failure(IOException("Empty response body"))
                    }
                } else {
                    val bodyString = response.body?.string()
                    Log.w(TAG, "OpenAI TTS failed: HTTP ${response.code}: $bodyString")
                    Result.failure(IOException("HTTP ${response.code}: $bodyString"))
                }
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /** Fetches the list of model ids — identical logic to OpenAiApiClient.listModels. */
    suspend fun listModels(apiKey: String, baseUrl: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val url = "${OpenAiApiClient.normalizeBaseUrl(baseUrl)}/models"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            try {
                okHttpClient.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string()
                    if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                    
                    val root = bodyString?.let { json.parseToJsonElement(it) }?.jsonObject
                    val models = root?.get("data")?.jsonArray?.mapNotNull { 
                        it.jsonObject["id"]?.jsonPrimitive?.content 
                    }.orEmpty()
                    Result.success(models)
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    /**
     * Fetches the list of voice ids from /v1/audio/voices — a common (though non-official)
     * convention among OpenAI-compatible TTS servers. Returns empty list if not supported.
     */
    suspend fun listVoices(apiKey: String, baseUrl: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val url = "${OpenAiApiClient.normalizeBaseUrl(baseUrl)}/audio/voices"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            try {
                okHttpClient.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string()
                    if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))

                    // Supports both a raw list of strings and OpenAI's typical {"data": [{"id": ...}]} shape.
                    val element = bodyString?.let { json.parseToJsonElement(it) }
                    val voices = if (element?.jsonArray != null) {
                        element.jsonArray.mapNotNull { it.jsonPrimitive.content }
                    } else {
                        element?.jsonObject?.get("data")?.jsonArray?.mapNotNull {
                            it.jsonObject["id"]?.jsonPrimitive?.content ?: it.jsonPrimitive.content
                        }.orEmpty()
                    }
                    Result.success(voices)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    @Serializable
    private data class SpeechRequest(
        val model: String,
        val voice: String,
        val input: String,
    )

    companion object {
        private const val TAG = "OpenAiTtsClient"
    }
}
