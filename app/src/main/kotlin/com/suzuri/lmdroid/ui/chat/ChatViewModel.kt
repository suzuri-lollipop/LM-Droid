package com.suzuri.lmdroid.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.MessageEntity
import com.suzuri.lmdroid.data.db.ModelOptionRow
import com.suzuri.lmdroid.data.repository.ApiProfileRepository
import com.suzuri.lmdroid.data.repository.ConversationRepository
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val apiProfileRepository: ApiProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // A StateFlow (not a plain var) so switching conversations can drive observeMessages via
    // flatMapLatest below, instead of only ever observing whichever conversation was active when
    // init{} first ran. null is a real, stable state here (not just "still loading"): it means
    // "a fresh new conversation that hasn't been written to the database yet" — see
    // ensureConversationId() and startNewConversation().
    private val conversationId = MutableStateFlow<Long?>(null)
    private var sendJob: Job? = null

    init {
        viewModelScope.launch {
            conversationId.value = conversationRepository.getInitialConversationId()
        }

        viewModelScope.launch {
            conversationId.filterNotNull().flatMapLatest { id ->
                conversationRepository.observeMessages(id)
            }.collect { entities ->
                _uiState.update { state -> state.copy(messages = entities.map { it.toUiModel() }) }
            }
        }

        // Drives the top bar title shown for the Chat screen (see MainActivity), so it tracks
        // the active conversation's title — including once the LLM-generated title lands.
        viewModelScope.launch {
            conversationId.filterNotNull().flatMapLatest { id ->
                conversationRepository.observeConversation(id)
            }.collect { conversation ->
                _uiState.update { state -> state.copy(conversationTitle = conversation?.title.orEmpty()) }
            }
        }

        viewModelScope.launch {
            settingsRepository.chatSettings.collect { settings ->
                _uiState.update { state ->
                    state.copy(apiKeyMissing = settings.apiKey.isNullOrBlank(), markdownEnabled = settings.markdownEnabled)
                }
            }
        }

        // Keeps the chat-screen model switcher in sync with every enabled profile's registered
        // models, and — the first time any become available with nothing explicitly chosen yet —
        // auto-picks one so merely enabling a profile is enough to start chatting, without also
        // having to visit the switcher.
        viewModelScope.launch {
            combine(
                apiProfileRepository.observeEnabledModelOptions(),
                settingsRepository.selectedChatModel,
            ) { options, selected -> options to selected }
                .collect { (options, selected) ->
                    _uiState.update { it.copy(availableModels = options, selectedModel = selected) }
                    if (selected == null && options.isNotEmpty()) {
                        val first = options.first()
                        settingsRepository.setSelectedChatModel(first.profileId, first.modelId)
                    }
                }
        }

        // Best-effort, once per app session: personalizes the empty-conversation suggestion rows
        // from past conversation topics. Starts at Loading (shown as a skeleton animation) and
        // resolves to Generated on success, or Fallback (static starter prompts) on failure or
        // when there's no history yet to base them on.
        viewModelScope.launch {
            val suggestions = conversationRepository.generateSuggestedPrompts()
            _uiState.update { state ->
                state.copy(
                    suggestionsState = if (!suggestions.isNullOrEmpty()) {
                        SuggestionsUiState.Generated(suggestions)
                    } else {
                        SuggestionsUiState.Fallback
                    },
                )
            }
        }
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    fun onSend() {
        val text = _uiState.value.input.trim()
        if (text.isEmpty() || _uiState.value.isStreaming) return

        _uiState.update { it.copy(input = "", errorMessage = null) }
        launchGeneration {
            val id = ensureConversationId()
            conversationRepository.sendUserMessage(id, text)
        }
    }

    /**
     * Lazily creates and persists the conversation the moment it's actually needed (the first
     * message being sent) — until then, conversationId stays null and nothing shows up in the
     * History list. Safe to call repeatedly: once a real id is assigned, it's reused.
     */
    private suspend fun ensureConversationId(): Long {
        conversationId.value?.let { return it }
        val newId = conversationRepository.createNewConversation()
        conversationId.value = newId
        return newId
    }

    /**
     * Edits a previously-sent user message and regenerates the reply from that point (ChatGPT/
     * Claude-style "edit and regenerate"). Cancels any in-progress generation first, since editing
     * implies the user wants a redo rather than the response currently streaming in.
     */
    fun onEditMessage(messageId: Long, newText: String) {
        val trimmed = newText.trim()
        val currentConversationId = conversationId.value ?: return
        if (trimmed.isEmpty()) return

        sendJob?.cancel()
        _uiState.update { it.copy(errorMessage = null) }
        launchGeneration { conversationRepository.editMessageAndRegenerate(currentConversationId, messageId, trimmed) }
    }

    /**
     * Regenerates an assistant reply without needing to edit the user's message first (a plain
     * "retry" affordance shown directly on the generated text).
     */
    fun onRegenerateResponse(messageId: Long) {
        val currentConversationId = conversationId.value ?: return

        sendJob?.cancel()
        _uiState.update { it.copy(errorMessage = null) }
        launchGeneration { conversationRepository.regenerateResponse(currentConversationId, messageId) }
    }

    fun onStopGeneration() {
        sendJob?.cancel()
    }

    /**
     * Updates immediately (rather than waiting for the settings Flow to round-trip through
     * DataStore) so the switch feels instant, and persists it so it's remembered next launch.
     */
    fun onMarkdownEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(markdownEnabled = enabled) }
        viewModelScope.launch { settingsRepository.saveMarkdownEnabled(enabled) }
    }

    /** Switches which enabled profile/model pair chat uses, from the switcher shown on this screen. */
    fun onSelectModel(option: ModelOptionRow) {
        viewModelScope.launch { settingsRepository.setSelectedChatModel(option.profileId, option.modelId) }
    }

    private fun launchGeneration(block: suspend () -> ConversationRepository.SendResult) {
        _uiState.update { it.copy(isStreaming = true) }
        sendJob = viewModelScope.launch {
            try {
                when (val result = block()) {
                    is ConversationRepository.SendResult.Success -> Unit
                    is ConversationRepository.SendResult.ApiKeyMissing ->
                        _uiState.update { it.copy(apiKeyMissing = true) }
                    is ConversationRepository.SendResult.Error ->
                        _uiState.update { it.copy(errorMessage = result.message) }
                }
            } finally {
                // Runs on normal completion, on error, and on cancellation (from onStopGeneration
                // or onEditMessage) alike — MutableStateFlow.update isn't a suspend call, so it's
                // safe here even though the job may already be cancelled.
                _uiState.update { it.copy(isStreaming = false) }
            }
        }
    }

    /**
     * Used by the History screen to check whether a conversation about to be deleted is the one
     * currently open here, so it can redirect this ViewModel to a fresh conversation instead of
     * leaving it pointed at a now-deleted row.
     */
    fun currentConversationId(): Long? = conversationId.value

    fun switchToConversation(id: Long) {
        sendJob?.cancel()
        conversationId.value = id
    }

    /**
     * Resets to a fresh, not-yet-persisted conversation (see ensureConversationId()) — nothing is
     * written to the database, and nothing shows up in History, until a message is actually sent
     * from it. This is a plain synchronous state reset (no suspend DB call), so mashing "new
     * conversation" repeatedly is harmless: every call just re-applies the same blank state.
     */
    fun startNewConversation() {
        sendJob?.cancel()
        conversationId.value = null
        _uiState.update {
            it.copy(messages = emptyList(), conversationTitle = "", input = "", errorMessage = null)
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun MessageEntity.toUiModel() = MessageUiModel(
        id = id,
        role = role,
        content = content,
        isError = isError,
        reasoningContent = reasoningContent,
    )
}
