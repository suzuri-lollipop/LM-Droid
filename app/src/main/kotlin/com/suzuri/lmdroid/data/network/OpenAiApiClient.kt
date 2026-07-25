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
                    if (!response.isSuccessful) {
                        val bodyString = try {
                            response.body?.string()
                        } catch (e: IOException) {
                            null
                        }
                        Log.w(TAG, "Error response ${response.code}: $bodyString")
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

                        val data = if (line.startsWith("data:")) {
                            line.removePrefix("data:").trim()
                        } else {
                            line
                        }

                        if (data == "[DONE]") {
                            trySend(StreamEvent.Done)
                            break
                        }
                        val chunk = runCatching {
                            json.decodeFromString(ChatCompletionChunk.serializer(), data)
                        }.onFailure { e ->
                            Log.w(TAG, "Failed to parse SSE chunk: $data", e)
                        }.getOrNull()
                        val choice = chunk?.choices?.firstOrNull()
                        // Reasoning/"thinking" models (e.g. Gemma reasoning variants, DeepSeek-R1
                        // style models) stream their chain-of-thought under reasoningContent as a
                        // separate event so the UI can show it in a collapsible "thinking" section
                        // rather than mixing it into the final answer.
                        val reasoningDelta = choice?.delta?.reasoningContent
                        if (!reasoningDelta.isNullOrEmpty()) {
                            trySend(StreamEvent.ReasoningDelta(reasoningDelta))
                        }
                        val delta = choice?.delta?.content ?: choice?.message?.content
                        if (!delta.isNullOrEmpty()) {
                            trySend(StreamEvent.Delta(delta))
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

    /**
     * A short, non-streaming completion used purely to auto-title a conversation from its first
     * exchange — best-effort, so callers should treat failure as "keep whatever title we already
     * have" rather than surface it to the user.
     */
    suspend fun generateTitle(
        apiKey: String,
        model: String,
        userMessage: String,
        assistantMessage: String?,
        baseUrl: String = DEFAULT_BASE_URL,
    ): Result<String> = withContext(Dispatchers.IO) {
        // Deliberately NOT replayed as separate user/assistant turns: a request ending on an
        // "assistant" message reads to some servers' chat templates (confirmed with a local
        // llama.cpp server) as "continue this assistant turn," which just echoes the given text
        // back nearly verbatim instead of following the system instruction. Folding the exchange
        // into a single user message keeps the request unambiguously "answer this," ending on user.
        val exchangeSummary = buildString {
            append("User: ").append(userMessage.take(2000))
            if (!assistantMessage.isNullOrBlank()) {
                append("\nAssistant: ").append(assistantMessage.take(2000))
            }
        }
        val messages = listOf(
            ChatMessageDto(role = "system", content = TITLE_SYSTEM_PROMPT),
            ChatMessageDto(role = "user", content = exchangeSummary),
        )
        val requestJson = json.encodeToString(
            ChatCompletionRequest.serializer(),
            // A reasoning/"thinking" model (see StreamEvent.ReasoningDelta) burns tokens on its
            // internal chain-of-thought before it ever writes the actual title, so a small budget
            // like 20 can be exhausted before any real content comes out, silently producing an
            // empty title. 500 gives room for that preamble on top of the few words we actually want.
            ChatCompletionRequest(model = model, messages = messages, stream = false, maxTokens = 500),
        )
        Log.d(TAG, "generateTitle request: $requestJson")

        val builderWithUrl = try {
            Request.Builder().url("${normalizeBaseUrl(baseUrl)}/chat/completions")
        } catch (e: IllegalArgumentException) {
            return@withContext Result.failure(e)
        }

        val request = try {
            builderWithUrl
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestJson.toRequestBody(jsonMediaType))
                .build()
        } catch (e: IllegalArgumentException) {
            return@withContext Result.failure(e)
        }

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string()
                if (!response.isSuccessful) {
                    val exception = mapToException(null, response.code, bodyString)
                    Log.w(TAG, "generateTitle failed: ${exception.userMessage}")
                    return@withContext Result.failure(exception)
                }
                // HttpLoggingInterceptor is HEADERS-only (see AppContainer), so the response body
                // never otherwise reaches Logcat — logging it here is the only way to see what
                // the model actually said versus what we asked for.
                Log.d(TAG, "generateTitle raw response: $bodyString")
                val chunk = bodyString?.let {
                    runCatching { json.decodeFromString(ChatCompletionChunk.serializer(), it) }
                        .onFailure { e -> Log.w(TAG, "Failed to parse generateTitle response: $it", e) }
                        .getOrNull()
                }
                val message = chunk?.choices?.firstOrNull()?.message
                val title = message?.content
                    ?.trim()
                    ?.trim('"', '「', '」', '『', '』', '.', '。')
                if (title.isNullOrBlank()) {
                    Log.w(
                        TAG,
                        "generateTitle produced no usable content for model=$model " +
                            "(reasoningContent=${message?.reasoningContent?.take(200)})",
                    )
                    Result.failure(OpenAiException.Unknown(null))
                } else {
                    Log.d(TAG, "generateTitle extracted title: \"$title\"")
                    Result.success(title)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "generateTitle failed with a network error", e)
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
        private const val TITLE_SYSTEM_PROMPT =
            "Reply with only a short conversation title (3 to 6 words, no quotes, no trailing " +
                "punctuation) summarizing the topic of the following exchange, in the same " +
                "language the user is writing in."

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
