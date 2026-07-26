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

@Serializable
data class ModelInfo(
    val id: String,
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
