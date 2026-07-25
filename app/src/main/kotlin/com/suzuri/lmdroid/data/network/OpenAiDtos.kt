package com.suzuri.lmdroid.data.network

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
    val content: MessageContent,
)

/** Convenience for the common case of a plain-text message (no image attachments). */
fun chatMessage(role: String, text: String): ChatMessageDto = ChatMessageDto(role = role, content = MessageContent.Text(text))

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
}

@Serializable
data class ImageUrl(val url: String)

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
