package com.suzuri.lmdroid.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.MessageEntity
import com.suzuri.lmdroid.data.repository.ConversationRepository
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var conversationId: Long = 0L

    init {
        viewModelScope.launch {
            conversationId = conversationRepository.getOrCreateDefaultConversation()

            launch {
                conversationRepository.observeMessages(conversationId).collect { entities ->
                    _uiState.update { state -> state.copy(messages = entities.map { it.toUiModel() }) }
                }
            }

            launch {
                settingsRepository.settings.collect { settings ->
                    _uiState.update { state -> state.copy(apiKeyMissing = settings.apiKey.isNullOrBlank()) }
                }
            }
        }
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    fun onSend() {
        val text = _uiState.value.input.trim()
        if (text.isEmpty() || _uiState.value.isStreaming) return

        _uiState.update { it.copy(input = "", isStreaming = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = conversationRepository.sendUserMessage(conversationId, text)) {
                is ConversationRepository.SendResult.Success -> Unit
                is ConversationRepository.SendResult.ApiKeyMissing ->
                    _uiState.update { it.copy(apiKeyMissing = true) }
                is ConversationRepository.SendResult.Error ->
                    _uiState.update { it.copy(errorMessage = result.message) }
            }
            _uiState.update { it.copy(isStreaming = false) }
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
    )
}
