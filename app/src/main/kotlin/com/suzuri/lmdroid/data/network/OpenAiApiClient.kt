package com.suzuri.lmdroid.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException

/**
 * Thin OkHttp-based client for the OpenAI Chat Completions API. Retrofit is deliberately not used
 * here because it doesn't natively support consuming a Server-Sent-Events stream as it arrives.
 */
class OpenAiApiClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = "https://api.openai.com/v1",
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun streamChatCompletion(
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
    ): Flow<StreamEvent> = callbackFlow {
        val requestBody = json.encodeToString(
            ChatCompletionRequest.serializer(),
            ChatCompletionRequest(model = model, messages = messages, stream = true),
        ).toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    trySend(StreamEvent.Done)
                    close()
                    return
                }
                val delta = runCatching {
                    json.decodeFromString(ChatCompletionChunk.serializer(), data)
                }.getOrNull()?.choices?.firstOrNull()?.delta?.content
                if (!delta.isNullOrEmpty()) {
                    trySend(StreamEvent.Delta(delta))
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // The response body must be read synchronously here, inside the callback —
                // reading it later would race OkHttp closing the response.
                val bodyString = try {
                    response?.body?.string()
                } catch (e: IOException) {
                    null
                }
                close(mapToException(t, response?.code, bodyString))
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(StreamEvent.Done)
                close()
            }
        }

        val eventSource = EventSources.createFactory(okHttpClient).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }.flowOn(Dispatchers.IO)

    suspend fun testApiKey(apiKey: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(mapToException(null, response.code, response.body?.string()))
                }
            }
        } catch (e: IOException) {
            Result.failure(OpenAiException.NetworkError(e))
        }
    }

    private fun mapToException(cause: Throwable?, httpCode: Int?, bodyString: String?): OpenAiException {
        if (httpCode == null) {
            return when (cause) {
                is IOException -> OpenAiException.NetworkError(cause)
                else -> OpenAiException.Unknown(cause)
            }
        }
        val serverMessage = bodyString?.let {
            runCatching { json.decodeFromString(OpenAiErrorBody.serializer(), it) }.getOrNull()?.error?.message
        }
        return when (httpCode) {
            401 -> OpenAiException.InvalidApiKey
            429 -> OpenAiException.RateLimited(retryAfterSeconds = null)
            400 -> OpenAiException.BadRequest(serverMessage ?: "リクエストが不正です。")
            in 500..599 -> OpenAiException.ServerError(httpCode)
            else -> OpenAiException.Unknown(cause)
        }
    }
}
