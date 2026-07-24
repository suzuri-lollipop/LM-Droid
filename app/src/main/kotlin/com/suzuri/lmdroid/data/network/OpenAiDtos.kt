package com.suzuri.lmdroid.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatMessageDto(
    val role: String,
    val content: String,
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean = true,
    // Caps worst-case generation length so a model that falls into a repetition loop (no
    // natural stop token) can't hang the request indefinitely. Kept low for now while
    // diagnosing a hang possibly caused by prompt_tokens + max_tokens exceeding n_ctx.
    @SerialName("max_tokens") val maxTokens: Int = 256,
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
