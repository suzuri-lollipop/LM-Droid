package com.suzuri.lmdroid.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.MessageEntity
import com.suzuri.lmdroid.data.repository.ConversationRepository
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // A StateFlow (not a plain var) so switching conversations can drive observeMessages via
    // flatMapLatest below, instead of only ever observing whichever conversation was active when
    // init{} first ran.
    private val conversationId = MutableStateFlow<Long?>(null)
    private var sendJob: Job? = null

    init {
        viewModelScope.launch {
            conversationId.value = conversationRepository.getOrCreateDefaultConversation()
        }

        viewModelScope.launch {
            conversationId.filterNotNull().flatMapLatest { id ->
                conversationRepository.observeMessages(id)
            }.collect { entities ->
                _uiState.update { state -> state.copy(messages = entities.map { it.toUiModel() }) }
            }
        }

        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { state -> state.copy(apiKeyMissing = settings.apiKey.isNullOrBlank()) }
            }
        }
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    fun onSend() {
        val text = _uiState.value.input.trim()
        val currentConversationId = conversationId.value ?: return
        if (text.isEmpty() || _uiState.value.isStreaming) return

        _uiState.update { it.copy(input = "", errorMessage = null) }
        launchGeneration { conversationRepository.sendUserMessage(currentConversationId, text) }
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

    fun onStopGeneration() {
        sendJob?.cancel()
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

    fun startNewConversation() {
        sendJob?.cancel()
        viewModelScope.launch {
            conversationId.value = conversationRepository.createNewConversation()
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
