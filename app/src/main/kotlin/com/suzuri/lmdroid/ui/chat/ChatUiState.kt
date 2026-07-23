package com.suzuri.lmdroid.ui.chat

import com.suzuri.lmdroid.data.db.MessageRole

data class MessageUiModel(
    val id: Long,
    val role: MessageRole,
    val content: String,
    val isError: Boolean,
)

data class ChatUiState(
    val messages: List<MessageUiModel> = emptyList(),
    val input: String = "",
    val isStreaming: Boolean = false,
    val apiKeyMissing: Boolean = false,
    val errorMessage: String? = null,
)
