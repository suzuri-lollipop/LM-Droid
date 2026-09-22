@file:OptIn(ExperimentalSerializationApi::class)

package com.suzuri.lmdroid.data.network

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ChatMessageDto(
    val role: String,
    // Nullable because an assistant message that only requests tool calls carries no content of
    // its own (per the OpenAI tool-calling shape), and a "tool" role message's actual payload
    // lives in `content` too (its result text) while being tagged by [toolCallId].
    val content: MessageContent? = null,
    // NEVER, not just relying on the (encodeDefaults=true) Json config: these two must stay
    // entirely absent from the JSON for the vast majority of messages (plain text, no tool use)
    // rather than sent as an explicit "tool_calls":null / "tool_call_id":null on every request —
    // several self-hosted OpenAI-compatible servers 500 when a message carries fields their
    // request schema doesn't defensively handle being null.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

/** Convenience for the common case of a plain-text message (no image attachments). */
fun chatMessage(role: String, text: String): ChatMessageDto = ChatMessageDto(role = role, content = MessageContent.Text(text))

/**
 * Folds every "system" message in [messages] into a single one at the very front, keeping the
 * order their contents appeared in and leaving every other message exactly as it was.
 *
 * Several system messages in one request are legal per OpenAI's spec, and this app does produce
 * them: the date-grounding line, one per active system prompt (Settings → システムプロンプト), plus
 * the forced-skill and skill-catalog lines. But a number of self-hosted OpenAI-compatible servers
 * accept a system message only as the request's one and only first entry and reject anything else
 * — LM Studio's local server answers `could not encode request: System message must be at the
 * beginning`, which the user saw as a reply that never started. One leading system message carries
 * the same content under both readings, so normalizing costs nothing and keeps the request
 * acceptable everywhere; it happens at the request-encoding boundary rather than in the caller so
 * no call site can forget it.
 */
fun collapseSystemMessages(messages: List<ChatMessageDto>): List<ChatMessageDto> {
    if (messages.none { it.role == SYSTEM_ROLE }) return messages
    val systemTexts = messages.mapNotNull(ChatMessageDto::systemText)
    val rest = messages.filterNot { it.role == SYSTEM_ROLE }
    return buildList {
        // An all-blank set still gets dropped rather than left in place out of order — the point
        // is that no system message may sit anywhere but at the front.
        if (systemTexts.isNotEmpty()) add(chatMessage(SYSTEM_ROLE, systemTexts.joinToString("\n\n")))
        addAll(rest)
    }
}

/** This message's text when it's a system message; null for any other role, or one carrying no text. */
private fun ChatMessageDto.systemText(): String? {
    if (role != SYSTEM_ROLE) return null
    return when (val body = content) {
        null -> null
        is MessageContent.Text -> body.text.takeIf { it.isNotBlank() }
        // Attachments on a system message have no meaning inside a merged preamble (nothing in this
        // app sends any today, but a SYSTEM row saved into an older conversation could) — and a
        // content *array* is itself a shape some of the servers above reject, so keep the text only.
        is MessageContent.Parts -> body.parts.filterIsInstance<ContentPart.TextPart>()
            .joinToString("\n\n") { it.text }
            .takeIf { it.isNotBlank() }
    }
}

private const val SYSTEM_ROLE = "system"

/** Echoes back a tool call the model previously requested — required by the OpenAI tool-calling protocol before the matching "tool" result message(s) below it. */
@Serializable
data class ToolCallDto(
    val id: String,
    val type: String = "function",
    val function: FunctionCallDto,
)

@Serializable
data class FunctionCallDto(
    val name: String,
    val arguments: String,
)

/**
 * A chat message's "content" is either a plain string (the common, text-only case) or — when the
 * message carries image attachments — a JSON array of typed parts, matching the OpenAI Chat
 * Completions vision request shape. [MessageContentSerializer] encodes this as a raw JSON string
 * or array, not a wrapped/tagged object, so a text-only message's wire format is unchanged from
 * before this type existed.
 */
@Serializable(with = MessageContentSerializer::class)
sealed class MessageContent {
    data class Text(val text: String) : MessageContent()
    data class Parts(val parts: List<ContentPart>) : MessageContent()
}

/** One part of a multimodal message's content array — see [MessageContent.Parts]. */
@Serializable
sealed class ContentPart {
    @Serializable
    @SerialName("text")
    data class TextPart(val text: String) : ContentPart()

    @Serializable
    @SerialName("image_url")
    data class ImagePart(@SerialName("image_url") val imageUrl: ImageUrl) : ContentPart()

    /** A recorded voice message, for audio-input-capable models — unsupported models reject this with an error. */
    @Serializable
    @SerialName("input_audio")
    data class AudioPart(@SerialName("input_audio") val inputAudio: InputAudio) : ContentPart()
}

@Serializable
data class ImageUrl(val url: String)

/** [data] is raw base64 audio bytes (no data-URI prefix) — [format] is a separate field, e.g. "wav". */
@Serializable
data class InputAudio(val data: String, val format: String)

object MessageContentSerializer : KSerializer<MessageContent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MessageContent")

    override fun serialize(encoder: Encoder, value: MessageContent) {
        val jsonEncoder = encoder as? JsonEncoder ?: error("MessageContent can only be encoded to JSON")
        val element: JsonElement = when (value) {
            is MessageContent.Text -> JsonPrimitive(value.text)
            is MessageContent.Parts ->
                jsonEncoder.json.encodeToJsonElement(ListSerializer(ContentPart.serializer()), value.parts)
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): MessageContent {
        // Only ever used for outgoing requests in this app — responses are parsed separately via
        // ChatCompletionChunk/Delta, so this path is never actually exercised at runtime.
        val jsonDecoder = decoder as? JsonDecoder ?: error("MessageContent can only be decoded from JSON")
        val element = jsonDecoder.decodeJsonElement()
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: element.toString()
        return MessageContent.Text(text)
    }
}

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean = true,
    // Caps worst-case generation length so a model that falls into a repetition loop (no
    // natural stop token) can't hang the request indefinitely.
    @SerialName("max_tokens") val maxTokens: Int = 4096,
    // Only non-null (and non-empty) when a tool-using feature (currently: web search) is enabled
    // and still allowed for this turn. EncodeDefault.Mode.NEVER keeps this field (and
    // ChatMessageDto's tool_calls/tool_call_id) entirely absent from the request whenever they're
    // null, overriding this app's Json(encodeDefaults = true) config for just these fields — a
    // server that's never heard of tool-calling shouldn't see "tools":null or an empty array on
    // every single request just because the feature happens to exist in this app.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tools: List<ToolDefinitionDto>? = null,
    // Only non-null when the chat screen's 思考(thinking) effort selector is set to LOW/MEDIUM/
    // XHIGH — left absent for OFF (which instead forces chat_template_kwargs.enable_thinking=
    // false, below) and for plain OpenAI-style models that never set this at all, so servers that
    // don't recognize the field never see it. See OpenAiApiClient.streamChatCompletion.
    @SerialName("reasoning_effort")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reasoningEffort: String? = null,
    // Only non-null when the user has explicitly turned the chat screen's 思考 effort selector to
    // OFF and/or the 記憶(memory) toggle off — left absent entirely whenever both are left at
    // their defaults so plain OpenAI and other servers that don't recognize this llama.cpp-specific
    // field never see it. See OpenAiApiClient.streamChatCompletion.
    @SerialName("chat_template_kwargs")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val chatTemplateKwargs: ChatTemplateKwargsDto? = null,
    // llama-server (llama.cpp)'s own top-level cap on how many tokens a reasoning-capable model
    // may spend in its thinking block. Only non-null when the user has raised the chat screen's
    // 思考予算(thinking budget) slider above 0 — left absent for 0 ("no explicit cap") and for
    // servers that never set this at all, so servers that don't recognize this llama-server-
    // specific field never see it. See OpenAiApiClient.streamChatCompletion.
    @SerialName("reasoning_budget_tokens")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reasoningBudgetTokens: Int? = null,
)

/**
 * llama-server (llama.cpp) forwards this object's fields straight into the model's Jinja chat
 * template as kwargs. `enable_thinking` is the switch reasoning-capable templates (e.g. Qwen3,
 * Gemma) branch on to suppress their `<think>...</think>` preamble; `enable_memory` is the
 * analogous switch for Qwen3.8-style templates that support persistent conversation memory. A
 * template that doesn't reference either simply ignores it. Each field is independently omittable
 * (EncodeDefault.Mode.NEVER) so forcing one off doesn't force the other to also be sent.
 */
@Serializable
data class ChatTemplateKwargsDto(
    @SerialName("enable_thinking")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val enableThinking: Boolean? = null,
    @SerialName("enable_memory")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val enableMemory: Boolean? = null,
)

/** One function the model may call, in OpenAI's tool-calling request shape. */
@Serializable
data class ToolDefinitionDto(
    val type: String = "function",
    val function: FunctionSchemaDto,
)

@Serializable
data class FunctionSchemaDto(
    val name: String,
    val description: String,
    /** A JSON Schema object describing the function's parameters, e.g. `{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}`. */
    val parameters: JsonElement,
)

@Serializable
data class ChatCompletionChunk(
    val choices: List<ChunkChoice> = emptyList(),
)

@Serializable
data class ChunkChoice(
    val delta: Delta = Delta(),
    val message: Delta? = null,
)

@Serializable
data class Delta(
    val content: String? = null,
    // Some models (e.g. reasoning/"thinking" models like Gemma's reasoning variants or
    // DeepSeek-R1-style models) stream their chain-of-thought under this separate field
    // instead of "content" while they're still "thinking".
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    // Streamed incrementally: a call's `arguments` string commonly arrives split across many
    // chunks, matched up by [ToolCallDeltaDto.index] rather than sent whole in one chunk — the
    // caller must accumulate these across the whole response (see OpenAiApiClient).
    @SerialName("tool_calls") val toolCalls: List<ToolCallDeltaDto>? = null,
)

@Serializable
data class ToolCallDeltaDto(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionCallDeltaDto? = null,
)

@Serializable
data class FunctionCallDeltaDto(
    val name: String? = null,
    val arguments: String? = null,
)

/** The `GET /models` response shape — used to auto-populate a profile's available models. */
@Serializable
data class ModelListResponse(
    val data: List<ModelInfo> = emptyList(),
)

/**
 * One entry of a `GET /models` response. Beyond [id], a number of servers also advertise which
 * request parameters the model actually accepts — used to decide, at registration time, which of
 * the chat screen's per-model controls (思考 effort / 思考予算 / 記憶) are meaningful for this
 * model (see [ModelCapabilities] and ApiModelEntity's supports* columns):
 *  - `supported_parameters` (string array): OpenRouter and a growing set of proxies/servers
 *    (vLLM, new-api, ...) list the request fields a model accepts;
 *  - `capabilities` (string→bool object): OpenAI's own model listing uses this shape.
 * Both are absent from plain OpenAI-style listings and from most self-hosted servers, in which
 * case caps fall back to the server's own report (see [ServerPropsDto]) or stay unknown.
 *
 * [capabilities] is deliberately a raw [JsonElement]: some servers (e.g. llama.cpp's LM
 * Studio-compatibility shape) use the same key for a *string array* instead, which a typed
 * `Map<String, Boolean>` would fail to parse — taking it raw keeps one oddball field from
 * breaking the whole model list.
 */
@Serializable
data class ModelInfo(
    val id: String,
    @SerialName("supported_parameters") val supportedParameters: List<String>? = null,
    val capabilities: JsonElement? = null,
)

/**
 * The subset of llama.cpp's `GET /props` we read when a server's `/models` entries carry no
 * per-model `supported_parameters`. llama-server serves `/props` at its root (not under `/v1`)
 * and describes the loaded model — which is exactly one model on a normal single-model server, so
 * its chat-template capabilities can be attributed to that model; router mode serves it per model
 * via `?model=`. Non-llama.cpp servers 404 (or answer something else entirely), which callers
 * treat as "no capability information".
 */
@Serializable
data class ServerPropsDto(
    // The loaded model's chat template source. A template that never references `enable_thinking`
    // / `enable_memory` ignores those chat_template_kwargs outright, so their presence in this
    // text is the only way to tell whether the 思考-OFF / 記憶 switches can do anything at all.
    @SerialName("chat_template") val chatTemplate: String? = null,
    // jinja::caps as reported by llama.cpp: keys like "supports_reasoning_effort" and
    // "supports_preserve_reasoning" mapped to booleans.
    @SerialName("chat_template_caps") val chatTemplateCaps: Map<String, Boolean>? = null,
)

@Serializable
data class OpenAiErrorBody(
    val error: OpenAiErrorDetail? = null,
)

@Serializable
data class OpenAiErrorDetail(
    val message: String? = null,
    val type: String? = null,
    val code: JsonElement? = null,
)
