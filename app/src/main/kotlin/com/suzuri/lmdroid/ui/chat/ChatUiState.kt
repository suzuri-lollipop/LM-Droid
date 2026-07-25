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

/**
 * The empty-conversation suggestion rows go through this instead of a plain list so the UI can
 * show a loading skeleton while the LLM call is in flight, rather than flashing the generic
 * static prompts first and swapping them out mid-animation.
 */
sealed class SuggestionsUiState {
    /** The one-shot, per-app-session personalization call (see ChatViewModel) hasn't resolved yet. */
    object Loading : SuggestionsUiState()

    /** LLM-generated from the topics of past conversations. */
    data class Generated(val prompts: List<String>) : SuggestionsUiState()

    /** Generation failed, or there was no history yet to base it on — falls back to static prompts. */
    object Fallback : SuggestionsUiState()
}

data class ChatUiState(
    val conversationTitle: String = "",
    val messages: List<MessageUiModel> = emptyList(),
    val input: String = "",
    val isStreaming: Boolean = false,
    val apiKeyMissing: Boolean = false,
    val errorMessage: String? = null,
    val markdownEnabled: Boolean = true,
    val suggestionsState: SuggestionsUiState = SuggestionsUiState.Loading,
    // Every model offered by every *enabled* profile — the chat-screen model switcher's choices.
    val availableModels: List<ModelOptionRow> = emptyList(),
    val selectedModel: SelectedModel? = null,
)
