package com.suzuri.lmdroid.data.network

import android.util.Log
import com.suzuri.lmdroid.data.db.ThinkingEffort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

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

    // Capability probing (GET /props) rides a derived client with a short call timeout: the main
    // okHttpClient deliberately has a 5-minute read timeout for streaming replies, which would
    // hang profile registration just as long on a server that accepts connections but never
    // answers an auxiliary endpoint. Deriving shares the connection pool/interceptors cheaply.
    private val propsOkHttpClient = okHttpClient.newBuilder()
        .callTimeout(PROPS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PROPS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun streamChatCompletion(
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        baseUrl: String = DEFAULT_BASE_URL,
        // Non-null only when a tool-using feature is enabled and still allowed for this turn
        // (see ConversationRepository's round cap) — offering the model a way to call out on its
        // own, rather than the app deciding unconditionally what to feed it ahead of time.
        tools: List<ToolDefinitionDto>? = null,
        // OFF explicitly asks the server to suppress thinking via chat_template_kwargs (see
        // ChatTemplateKwargsDto); LOW/MEDIUM/XHIGH are instead sent as the top-level
        // reasoning_effort field, leaving the model/server free to think at that level by
        // whatever mechanism it actually supports.
        thinkingEffort: ThinkingEffort = ThinkingEffort.MEDIUM,
        // True (default) leaves the model/server at its own default memory behavior; false
        // explicitly asks it to suppress persistent memory via chat_template_kwargs — see
        // ChatTemplateKwargsDto. There's no "force true" distinct from "leave at default" since no
        // server-side default actually needs overriding upward.
        memoryEnabled: Boolean = true,
        // 0 (default) means "no explicit cap" and is left off the request entirely; > 0 is sent
        // as the top-level reasoning_budget_tokens field (llama-server/llama.cpp) capping how many
        // tokens a reasoning-capable model may spend in its thinking block. See AppSettings.thinkingBudget.
        thinkingBudget: Int = 0,
    ): Flow<StreamEvent> = callbackFlow {
        val enableThinkingKwarg = if (thinkingEffort == ThinkingEffort.OFF) false else null
        val enableMemoryKwarg = if (memoryEnabled) null else false
        val kwargs = if (enableThinkingKwarg != null || enableMemoryKwarg != null) {
            ChatTemplateKwargsDto(enableThinking = enableThinkingKwarg, enableMemory = enableMemoryKwarg)
        } else {
            null
        }
        val reasoningEffort = when (thinkingEffort) {
            ThinkingEffort.OFF -> null
            ThinkingEffort.LOW -> "low"
            ThinkingEffort.MEDIUM -> "medium"
            ThinkingEffort.XHIGH -> "xhigh"
        }
        val requestJson = json.encodeToString(
            ChatCompletionRequest.serializer(),
            ChatCompletionRequest(
                model = model,
                // See collapseSystemMessages: one leading system message only, for servers that
                // reject a request whose system message is anything but the single first entry.
                messages = collapseSystemMessages(messages),
                stream = true,
                tools = tools,
                reasoningEffort = reasoningEffort,
                chatTemplateKwargs = kwargs,
                reasoningBudgetTokens = thinkingBudget.takeIf { it > 0 },
            ),
        )
        // Deliberately the tool names and the encoded body size, not the body itself — the latter can
        // carry a full conversation history plus base64 image/audio attachments, which would either
        // spam Logcat across many lines or get silently truncated by its per-line limit. The names
        // answer "was this tool offered this turn"; body= is how many bytes have to be uploaded
        // before the server can even start prefilling, i.e. the number to correlate against "how
        // long until the first token" once a conversation has grown or carries images.
        Log.d(TAG, "streamChatCompletion: model=$model, tools=${tools?.map { it.function.name }}, body=${requestJson.length}")
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

                    // Keyed by the fragment's index (its position among the calls the model is
                    // making this turn, not a char offset) — a call's `id`/`function.name` usually
                    // arrive whole on the first fragment while `arguments` streams in piece by
                    // piece across many chunks, so everything must be reassembled before it's
                    // usable. A LinkedHashMap preserves the order calls were first seen in.
                    val toolCallAccumulators = LinkedHashMap<Int, ToolCallAccumulator>()
                    var sawFirstLine = false

                    while (isActive && !source.exhausted()) {
                        val rawLine = source.readUtf8Line() ?: break
                        // Server-side time-to-first-byte, straight from Logcat: the gap between the
                        // repository's "request prepared" line and this one is request encoding +
                        // connection setup + the server's queue/prefill — everything the app waits
                        // on but does not cause. Compare "first reasoning/content delta below" to
                        // see what parsing, Room and the UI then add on our side.
                        if (!sawFirstLine) {
                            sawFirstLine = true
                            Log.d(TAG, "first SSE line received")
                        }
                        val line = rawLine.trim()
                        if (line.isEmpty()) continue

                        val data = if (line.startsWith("data:")) {
                            line.removePrefix("data:").trim()
                        } else {
                            line
                        }

                        if (data == "[DONE]") {
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
                        val toolCallFragments = choice?.delta?.toolCalls ?: choice?.message?.toolCalls
                        toolCallFragments?.forEach { fragment ->
                            val accumulator = toolCallAccumulators.getOrPut(fragment.index) { ToolCallAccumulator() }
                            fragment.id?.let { accumulator.id = it }
                            fragment.function?.name?.let { accumulator.name = accumulator.name.orEmpty() + it }
                            fragment.function?.arguments?.let { accumulator.arguments.append(it) }
                        }
                    }
                    if (toolCallAccumulators.isNotEmpty()) {
                        val requestedToolCalls = toolCallAccumulators.values.mapNotNull { accumulator ->
                            val id = accumulator.id
                            val name = accumulator.name
                            if (id == null || name == null) {
                                Log.w(TAG, "Dropping incomplete tool call (id=$id, name=$name)")
                                null
                            } else {
                                RequestedToolCall(id = id, name = name, argumentsJson = accumulator.arguments.toString())
                            }
                        }
                        if (requestedToolCalls.isNotEmpty()) {
                            trySend(StreamEvent.ToolCallsRequested(requestedToolCalls))
                        }
                    }
                    trySend(StreamEvent.Done)
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
     * Fetches the list of models a provider offers (to auto-populate a profile's models rather
     * than requiring the user to type one in) together with, on a best-effort basis, what each
     * model can actually be configured to do — see [ModelCapabilities].
     *
     * Two capability sources are consulted, both optional:
     *  1. per-entry `supported_parameters` / `capabilities` metadata in the `/models` response
     *     itself (OpenRouter, vLLM, some proxies...);
     *  2. llama.cpp's `GET /props` (served at the server's root, not under `/v1`), whose
     *     `chat_template_caps` / `chat_template` describe the loaded model — tried only for
     *     models the first source left unresolved.
     * A server that advertises neither yields all-null (unknown) capabilities, which downstream
     * code treats as "offer everything" — exactly how every server behaved before this existed.
     */
    suspend fun listModels(apiKey: String, baseUrl: String = DEFAULT_BASE_URL): Result<List<ModelCapabilities>> =
        withContext(Dispatchers.IO) {
            val fetched = fetchModelList(apiKey, baseUrl)
            if (fetched.isFailure) return@withContext Result.failure(fetched.exceptionOrNull()!!)
            val entries = fetched.getOrNull()!!
            val caps = entries.map(::capsFromModelEntry).toMutableList()
            // Servers that advertise nothing (plain OpenAI shape) or only partially describe
            // their models still get the /props fallback; fully-described ones cost zero extra
            // requests.
            if (caps.any(::hasUnknownCaps)) {
                attachPropsCaps(apiKey, baseUrl, entries.map { it.id }, caps)
            }
            Log.d(TAG, "listModels extracted ${caps.size} model(s): $caps")
            Result.success(caps)
        }

    private suspend fun fetchModelList(apiKey: String, baseUrl: String): Result<List<ModelInfo>> =
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
                    val bodyString = response.body?.string()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "listModels failed: HTTP ${response.code}: ${bodyString?.take(300)}")
                        return@withContext Result.failure(mapToException(null, response.code, bodyString))
                    }
                    Log.d(TAG, "listModels raw response: $bodyString")
                    val parsed = bodyString?.let {
                        runCatching { json.decodeFromString(ModelListResponse.serializer(), it) }
                            .onFailure { e -> Log.w(TAG, "Failed to parse listModels response: $it", e) }
                            .getOrNull()
                    }
                    if (parsed == null) {
                        // Distinct from "the server genuinely has zero models" — this is "the
                        // response didn't match the shape we expected," which must not silently
                        // resolve to an empty (falsely "successful") model list.
                        return@withContext Result.failure(IOException("Unable to parse the model list response"))
                    }
                    Result.success(parsed.data)
                }
            } catch (e: IOException) {
                Result.failure(OpenAiException.NetworkError(e))
            }
        }

    /**
     * Derives a model's capabilities from what its own `/models` entry advertises. A null result
     * for one of the four flags means "this entry says nothing about it" (as opposed to false —
     * "this model demonstrably can't do it"), which keeps half-describing servers from hiding
     * controls they never actually ruled out.
     */
    private fun capsFromModelEntry(info: ModelInfo): ModelCapabilities {
        var thinking: Boolean? = null
        var effort: Boolean? = null
        var budget: Boolean? = null
        var memory: Boolean? = null
        info.supportedParameters?.let { params ->
            val names = params.map { it.lowercase() }.toSet()
            effort = "reasoning_effort" in names
            // A model listing any reasoning knob at all can think; the effort field additionally
            // implies it accepts the ON side of the 思考 switch (reasoning_effort carries it).
            thinking = effort || setOf("reasoning", "include_reasoning", "enable_thinking", "thinking").any { it in names }
            budget = "reasoning_budget_tokens" in names || "thinking_budget" in names
            // No known server advertises persistent memory in supported_parameters today; if one
            // starts naming the enable_memory kwarg here, honor it.
            memory = "enable_memory" in names
        }
        // OpenAI-style `capabilities: {"supports_...": true, ...}` object, tolerated per the
        // ModelInfo.capabilities comment.
        val capsObject = (info.capabilities as? JsonObject)
            ?.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.content?.toBooleanStrictOrNull()?.let { key to it }
            }
            ?.toMap()
        if (capsObject != null) {
            thinking = thinking ?: capsObject["supports_thinking"] ?: capsObject["thinking"]
            effort = effort ?: capsObject["supports_reasoning_effort"] ?: capsObject["reasoning_effort"]
            budget = budget ?: capsObject["supports_reasoning_budget"]
            memory = memory ?: capsObject["supports_memory"]
        }
        return ModelCapabilities(
            modelId = info.id,
            supportsThinking = thinking,
            supportsReasoningEffort = effort,
            supportsThinkingBudget = budget,
            supportsMemory = memory,
        )
    }

    private fun hasUnknownCaps(caps: ModelCapabilities): Boolean =
        caps.supportsThinking == null || caps.supportsReasoningEffort == null ||
            caps.supportsThinkingBudget == null || caps.supportsMemory == null

    /**
     * Fills the unknowns in [caps] from llama.cpp's `/props` — silently, since a non-llama.cpp
     * server simply doesn't have that endpoint. A single-model server serves `/props` at its
     * root and it describes the one loaded model; router mode needs `?model=` per instance (with
     * `autoload=false` so merely *querying* capabilities never triggers loading a heavy model).
     * Router probing is only attempted for as long as it keeps working, and capped at
     * [MAX_PROPS_PER_MODEL_PROBES] so a server that isn't really llama.cpp still costs a couple
     * of failed requests rather than one per model.
     */
    private fun attachPropsCaps(apiKey: String, baseUrl: String, modelIds: List<String>, caps: MutableList<ModelCapabilities>) {
        if (modelIds.isEmpty()) return
        val propsUrl = propsBaseUrl(baseUrl)
        if (modelIds.size == 1) {
            // Single-model server: its root /props describes exactly that model.
            fetchProps(apiKey, propsUrl, modelId = null)?.let { mergePropsCaps(caps, 0, capsFromProps(it)) }
            return
        }
        // Multi-model (llama.cpp router mode): the root /props — if it answers at all — describes
        // one arbitrary instance rather than every listed model, so attribute per model via
        // ?model= instead, starting with a probe of the first model to confirm the endpoint
        // exists before spending one request per remaining model (capped regardless).
        val firstProps = fetchProps(apiKey, propsUrl, modelId = modelIds[0]) ?: return
        mergePropsCaps(caps, 0, capsFromProps(firstProps))
        modelIds.drop(1).take(MAX_PROPS_PER_MODEL_PROBES).forEachIndexed { index, modelId ->
            // A model that's currently unloaded answers nothing (autoload=false above); that's
            // simply "unknown for now", not a reason to abandon the rest of the list.
            val props = fetchProps(apiKey, propsUrl, modelId = modelId) ?: return@forEachIndexed
            mergePropsCaps(caps, index + 1, capsFromProps(props))
        }
    }

    private fun fetchProps(apiKey: String, propsUrl: String, modelId: String?): ServerPropsDto? {
        val url = buildString {
            append(propsUrl)
            if (modelId != null) {
                append("?model=").append(java.net.URLEncoder.encode(modelId, "UTF-8")).append("&autoload=false")
            }
        }
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            // Capability discovery is a nicety, never worth stalling profile save on: a shared
            // client with a long read timeout would hang for minutes on a server that accepts the
            // connection but never answers /props.
            propsOkHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()?.let { body ->
                    runCatching { json.decodeFromString(ServerPropsDto.serializer(), body) }
                        .onFailure { e -> Log.w(TAG, "Failed to parse /props response: ${body.take(300)}", e) }
                        .getOrNull()
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * `/props` is served at the server's root while chat/models live under `/v1` — so the `/v1`
     * (or `/v1/`) tail users conventionally include in their baseUrl is stripped back off first.
     */
    private fun propsBaseUrl(baseUrl: String): String =
        normalizeBaseUrl(baseUrl).removeSuffix("/v1") + "/props"

    private fun capsFromProps(props: ServerPropsDto): ModelCapabilities {
        val template = props.chatTemplate
        val serverCaps = props.chatTemplateCaps
        // An all-null /props answer (e.g. a server that implements the endpoint for something
        // else entirely) must not flip anything to a confident true/false.
        if (template == null && serverCaps == null) return ModelCapabilities(modelId = "")
        return ModelCapabilities(
            modelId = "",
            // The 思考-OFF path is chat_template_kwargs.enable_thinking — a template that never
            // mentions it ignores the kwarg, so its text is the ground truth.
            supportsThinking = template?.contains("enable_thinking"),
            supportsReasoningEffort = serverCaps?.get("supports_reasoning_effort"),
            // reasoning_budget_tokens is a llama-server field, and /props answering means this is
            // llama-server. (An old build that predates the field ignores it exactly as it does
            // today — no behavior change versus before caps existed.)
            supportsThinkingBudget = true,
            supportsMemory = template?.contains("enable_memory"),
        )
    }

    /** Fills only the flags [caps] leaves unknown at [index] with the /props-derived [props] values. */
    private fun mergePropsCaps(caps: MutableList<ModelCapabilities>, index: Int, props: ModelCapabilities) {
        val existing = caps[index]
        caps[index] = existing.copy(
            supportsThinking = existing.supportsThinking ?: props.supportsThinking,
            supportsReasoningEffort = existing.supportsReasoningEffort ?: props.supportsReasoningEffort,
            supportsThinkingBudget = existing.supportsThinkingBudget ?: props.supportsThinkingBudget,
            supportsMemory = existing.supportsMemory ?: props.supportsMemory,
        )
    }

    /**
     * A short, non-streaming completion used purely to auto-title a conversation from the user's
     * own first message — best-effort, so callers should treat failure as "keep whatever title we
     * already have" rather than surface it to the user. Deliberately based on just the prompt
     * (not the assistant's reply): the topic is already fully expressed in what the user asked,
     * and it lets the title be generated in parallel with the assistant's reply instead of after
     * it finishes.
     */
    suspend fun generateTitle(
        apiKey: String,
        model: String,
        userMessage: String,
        baseUrl: String = DEFAULT_BASE_URL,
    ): Result<String> = withContext(Dispatchers.IO) {
        val messages = listOf(
            chatMessage(role = "system", text = TITLE_SYSTEM_PROMPT),
            chatMessage(role = "user", text = userMessage.take(2000)),
        )
        val requestJson = json.encodeToString(
            ChatCompletionRequest.serializer(),
            // This request goes out alongside (now: just after) the real reply's, against the same
            // server — and on a single-slot local server every token it spends is a token the reply
            // is not producing. So thinking is explicitly suppressed here (a template that doesn't
            // know the kwarg ignores it, see ChatTemplateKwargsDto): a title needs the topic, not a
            // chain of thought. maxTokens stays generous rather than tight, because a server that
            // *ignores* the kwarg above still burns its thinking preamble first, and a budget small
            // enough to be polite would run out before any real title text came out — an empty
            // title (see the 400 below) is a worse outcome than the few wasted tokens.
            ChatCompletionRequest(
                model = model,
                messages = messages,
                stream = false,
                maxTokens = 500,
                chatTemplateKwargs = ChatTemplateKwargsDto(enableThinking = false),
            ),
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
                "punctuation) summarizing the topic of the following user message, in the same " +
                "language the user is writing in."
        // Capability probing is a best-effort nicety bolted onto registration — never let a
        // dead-but-connected server stall it for minutes (see propsOkHttpClient).
        private const val PROPS_TIMEOUT_SECONDS = 8L
        // Upper bound on router-mode "/props?model=" probes during one registration, so even a
        // server with a hundred models can't turn model registration into a hundred requests.
        private const val MAX_PROPS_PER_MODEL_PROBES = 20

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

/**
 * What one model reported as configurable about its own thinking behavior, discovered at
 * registration time (see [listModels]) and persisted onto [com.suzuri.lmdroid.data.db.ApiModelEntity]
 * (ApiProfileRepository.refreshModels). Each flag maps to one control in the chat screen's model
 * menu (see ModelSelectorButton): [supportsThinking] = the OFF side of 思考
 * (chat_template_kwargs.enable_thinking), [supportsReasoningEffort] = the LOW/MEDIUM/XHIGH levels
 * (reasoning_effort), [supportsThinkingBudget] = 思考予算 (reasoning_budget_tokens),
 * [supportsMemory] = 記憶 (chat_template_kwargs.enable_memory).
 *
 * null means "the server said nothing about this" — deliberately distinct from false ("said it's
 * not supported"): unknown controls stay visible, so a server that advertises nothing behaves
 * exactly as it did before per-model capabilities existed.
 */
data class ModelCapabilities(
    val modelId: String,
    val supportsThinking: Boolean? = null,
    val supportsReasoningEffort: Boolean? = null,
    val supportsThinkingBudget: Boolean? = null,
    val supportsMemory: Boolean? = null,
)

/** Mutable, in-progress reassembly of one streamed tool call — see [StreamEvent.ToolCallsRequested]. */
private class ToolCallAccumulator {
    var id: String? = null
    var name: String? = null
    val arguments: StringBuilder = StringBuilder()
}
