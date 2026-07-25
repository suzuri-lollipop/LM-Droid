package com.suzuri.lmdroid.ui.chat

import com.suzuri.lmdroid.data.db.MessageRole

data class MessageUiModel(
    val id: Long,
    val role: MessageRole,
    val content: String,
    val isError: Boolean,
    val reasoningContent: String? = null,
)

data class ChatUiState(
    val conversationTitle: String = "",
    val messages: List<MessageUiModel> = emptyList(),
    val input: String = "",
    val isStreaming: Boolean = false,
    val apiKeyMissing: Boolean = false,
    val errorMessage: String? = null,
    val markdownEnabled: Boolean = true,
    val suggestedPrompts: List<String> = emptyList(),
)
