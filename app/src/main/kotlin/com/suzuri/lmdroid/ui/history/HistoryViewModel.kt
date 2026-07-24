package com.suzuri.lmdroid.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.ConversationEntity
import com.suzuri.lmdroid.data.repository.ConversationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val conversationRepository: ConversationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            conversationRepository.observeConversations().collect { conversations ->
                _uiState.update { it.copy(conversations = conversations.map { entity -> entity.toUiModel() }) }
            }
        }
    }

    fun onDeleteConversation(id: Long) {
        viewModelScope.launch {
            conversationRepository.deleteConversation(id)
        }
    }

    private fun ConversationEntity.toUiModel() = ConversationUiModel(id = id, title = title, updatedAt = updatedAt)
}
