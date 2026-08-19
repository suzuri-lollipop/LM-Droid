package com.suzuri.lmdroid.ui.chat

import com.suzuri.lmdroid.data.db.MessageRole
import com.suzuri.lmdroid.data.db.ModelOptionRow
import com.suzuri.lmdroid.data.db.ThinkingEffort
import com.suzuri.lmdroid.data.db.ThinkingTimelineEntry
import com.suzuri.lmdroid.data.settings.SelectedModel

data class MessageUiModel(
    val id: Long,
    val role: MessageRole,
    val content: String,
    val isError: Boolean,
    // Chain-of-thought reasoning and tool activity (web search / page fetches), in the order they
    // actually happened — see ThinkingTimelineEntry.
    val thinkingTimeline: List<ThinkingTimelineEntry> = emptyList(),
    val attachments: List<MessageAttachmentUiModel> = emptyList(),
)

/** One image or voice-message attachment on an already-sent message. */
data class MessageAttachmentUiModel(
    val filePath: String,
    val mimeType: String,
)

/** An image or voice message attached to the composer but not sent yet — [id] is a local key for removal, distinct from any DB row. */
data class PendingAttachmentUiModel(
    val id: String,
    val filePath: String,
    val mimeType: String,
)

/** One saved system prompt, as offered by the composer's selector dialog — see SystemPromptRepository. */
data class SystemPromptOptionUiModel(
    val id: Long,
    val name: String,
)

/** One saved skill, as offered by the composer's selector dialog — see SkillRepository. */
data class SkillOptionUiModel(
    val id: Long,
    val name: String,
    val description: String,
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
    val thinkingEffort: ThinkingEffort = ThinkingEffort.MEDIUM,
    val memoryEnabled: Boolean = true,
    // See AppSettings.thinkingBudget — 0 (default) means "no explicit cap (server default)".
    val thinkingBudget: Int = 0,
    val suggestionsState: SuggestionsUiState = SuggestionsUiState.Loading,
    // Every model offered by every *enabled* profile — the chat-screen model switcher's choices.
    val availableModels: List<ModelOptionRow> = emptyList(),
    val selectedModel: SelectedModel? = null,
    // Images picked via the composer's attach button, staged until the message is actually sent.
    val pendingAttachments: List<PendingAttachmentUiModel> = emptyList(),
    // True while the mic's long-press-to-record gesture is actively capturing audio.
    val isRecordingVoiceMessage: Boolean = false,
    // Every saved system prompt, and which ones (zero or more) are currently active — see
    // SystemPromptRepository.
    val systemPrompts: List<SystemPromptOptionUiModel> = emptyList(),
    val selectedSystemPromptIds: Set<Long> = emptySet(),
    // Every saved skill, and which ones (zero or more) are currently active/advertised to the
    // model — see SkillRepository. [forcedSkillId] is separate: a skill the user explicitly
    // picked (via SkillDialog's "使う" action) to force into just the next message, regardless of
    // whether it's in the active set — shown as a removable chip above the composer.
    val skills: List<SkillOptionUiModel> = emptyList(),
    val selectedSkillIds: Set<Long> = emptySet(),
    val forcedSkillId: Long? = null,
)
