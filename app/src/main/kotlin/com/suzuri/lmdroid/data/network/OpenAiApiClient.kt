package com.suzuri.lmdroid.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Thin OkHttp-based client for the OpenAI Chat Completions API (or any OpenAI-compatible
 * endpoint, since [baseUrl] is caller-supplied).
 *
 * The SSE stream is parsed by hand (reading "data: ..." lines directly off the response body)
 * rather than via the `okhttp-sse` library, because that library rejects any response whose
 * Content-Type header isn't exactly "text/event-stream" — and a number of self-hosted
 * OpenAI-compatible servers (local LLM runners, proxies, etc.) send a correctly-formatted SSE
 * body without setting that exact header, which made those otherwise-working servers fail with
 * an opaque error. Retrofit is not used here for the same underlying reason: neither it nor
 * okhttp-sse tolerates a real-world server that doesn't match OpenAI's exact response shape.
 */
class OpenAiApiClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun streamChatCompletion(
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        baseUrl: String = DEFAULT_BASE_URL,
    ): Flow<StreamEvent> = callbackFlow {
        val requestJson = json.encodeToString(
            ChatCompletionRequest.serializer(),
            ChatCompletionRequest(model = model, messages = messages, stream = true),
        )
        Log.d(TAG, "Request: $requestJson")
        Log.i(TAG, "!!! LM-DROID-DEBUG !!! Request Body: $requestJson")
        val requestBody = requestJson.toRequestBody(jsonMediaType)

        val builderWithUrl = try {
            Request.Builder().url("${normalizeBaseUrl(baseUrl)}/chat/completions")
        } catch (e: IllegalArgumentException) {
            close(OpenAiException.BadRequest("APIのURLが不正です: ${e.message}"))
            return@callbackFlow
        }

        val request = try {
            builderWithUrl
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()
        } catch (e: IllegalArgumentException) {
            close(OpenAiException.BadRequest("APIキーに使用できない文字が含まれています: ${e.message}"))
            return@callbackFlow
        }

        val call = okHttpClient.newCall(request)

        launch {
            try {
                call.execute().use { response ->
                    Log.i(TAG, "!!! LM-DROID-DEBUG !!! Response Code: ${response.code}")
                    Log.i(TAG, "!!! LM-DROID-DEBUG !!! Response Content-Type: ${response.header("Content-Type")}")

                    if (!response.isSuccessful) {
                        val bodyString = try {
                            response.body?.string()
                        } catch (e: IOException) {
                            null
                        }
                        Log.e(TAG, "Error Response (${response.code}): $bodyString")
                        close(mapToException(null, response.code, bodyString))
                        return@use
                    }

                    val source = response.body?.source()
                    if (source == null) {
                        close(OpenAiException.Unknown(null))
                        return@use
                    }

                    while (isActive && !source.exhausted()) {
                        val rawLine = source.readUtf8Line() ?: break
                        val line = rawLine.trim()
                        if (line.isEmpty()) continue

                        Log.d(TAG, "SSE line: $line")
                        Log.i(TAG, "!!! LM-DROID-DEBUG !!! SSE Line: $line")

                        val data = if (line.startsWith("data:")) {
                            line.removePrefix("data:").trim()
                        } else {
                            line
                        }

                        if (data == "[DONE]") {
                            Log.i(TAG, "!!! LM-DROID-DEBUG !!! Received [DONE]")
                            trySend(StreamEvent.Done)
                            break
                        }
                        val chunk = runCatching {
                            json.decodeFromString(ChatCompletionChunk.serializer(), data)
                        }.onFailure { e ->
                            Log.w(TAG, "Failed to parse SSE chunk: $data", e)
                            Log.i(TAG, "!!! LM-DROID-DEBUG !!! Failed to parse JSON: $data")
                        }.getOrNull()
                        val choice = chunk?.choices?.firstOrNull()
                        val delta = choice?.delta?.content ?: choice?.message?.content
                        Log.i(TAG, "!!! LM-DROID-DEBUG !!! Parsed delta: $delta")
                        if (!delta.isNullOrEmpty()) {
                            val result = trySend(StreamEvent.Delta(delta))
                            Log.i(TAG, "!!! LM-DROID-DEBUG !!! trySend result: $result")
                        }
                    }
                    close()
                }
            } catch (e: IOException) {
                close(OpenAiException.NetworkError(e))
            } catch (e: Exception) {
                close(OpenAiException.Unknown(e))
            }
        }

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    suspend fun testApiKey(apiKey: String, baseUrl: String = DEFAULT_BASE_URL): Result<Unit> =
        withContext(Dispatchers.IO) {
            val builderWithUrl = try {
                Request.Builder().url("${normalizeBaseUrl(baseUrl)}/models")
            } catch (e: IllegalArgumentException) {
                return@withContext Result.failure(OpenAiException.BadRequest("APIのURLが不正です: ${e.message}"))
            }

            val request = try {
                builderWithUrl.addHeader("Authorization", "Bearer $apiKey").get().build()
            } catch (e: IllegalArgumentException) {
                return@withContext Result.failure(
                    OpenAiException.BadRequest("APIキーに使用できない文字が含まれています: ${e.message}"),
                )
            }

            try {
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
        val serverMessage = extractServerMessage(bodyString)
        return when (httpCode) {
            401 -> OpenAiException.InvalidApiKey
            429 -> OpenAiException.RateLimited(retryAfterSeconds = null)
            400 -> OpenAiException.BadRequest(serverMessage ?: "リクエストが不正です。")
            in 500..599 -> OpenAiException.ServerError(httpCode, serverMessage)
            else -> OpenAiException.Unknown(cause)
        }
    }

    /**
     * Not every OpenAI-compatible server replies with OpenAI's exact `{"error":{"message":...}}`
     * error shape (e.g. a self-hosted server may return its own JSON shape, or plain text/a stack
     * trace). Fall back to the raw body so the user still sees *something* actionable.
     */
    private fun extractServerMessage(bodyString: String?): String? {
        if (bodyString.isNullOrBlank()) return null
        val jsonMessage = runCatching {
            json.decodeFromString(OpenAiErrorBody.serializer(), bodyString)
        }.getOrNull()?.error?.message
        return jsonMessage ?: bodyString.trim().take(300)
    }

    companion object {
        private const val TAG = "OpenAiApiClient"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        /**
         * Users commonly type a bare host (e.g. "100.97.208.27:721/v1") for a self-hosted
         * OpenAI-compatible server and forget the scheme, which OkHttp's URL parser rejects
         * outright. Default a missing scheme to plain http, since a self-hosted/local endpoint
         * is far more likely to be unencrypted than to be https.
         */
        internal fun normalizeBaseUrl(rawBaseUrl: String): String {
            val trimmed = rawBaseUrl.trim().trimEnd('/')
            return if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "http://$trimmed"
            }
        }
    }
}
