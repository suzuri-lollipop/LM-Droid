package com.suzuri.lmdroid.ui.chat

import com.suzuri.lmdroid.data.db.MessageRole
import com.suzuri.lmdroid.data.db.ModelOptionRow
import com.suzuri.lmdroid.data.settings.SelectedModel

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
    // Every model offered by every *enabled* profile — the chat-screen model switcher's choices.
    val availableModels: List<ModelOptionRow> = emptyList(),
    val selectedModel: SelectedModel? = null,
)
