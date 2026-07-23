package com.suzuri.lmdroid.data.network

import kotlinx.serialization.Serializable

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
)

@Serializable
data class ChatCompletionChunk(
    val choices: List<ChunkChoice> = emptyList(),
)

@Serializable
data class ChunkChoice(
    val delta: Delta = Delta(),
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
    val code: String? = null,
)
