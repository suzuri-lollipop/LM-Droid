package com.suzuri.lmdroid.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.ConversationEntity
import com.suzuri.lmdroid.data.db.FolderEntity
import com.suzuri.lmdroid.data.repository.ConversationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val conversationRepository: ConversationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    // null = "all conversations" (no folder filter); drives the conversation list via
    // flatMapLatest below so switching folders re-subscribes to the right query.
    private val selectedFolderId = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            selectedFolderId.flatMapLatest { folderId ->
                if (folderId == null) {
                    conversationRepository.observeConversations()
                } else {
                    conversationRepository.observeConversationsInFolder(folderId)
                }
            }.collect { conversations ->
                _uiState.update { it.copy(conversations = conversations.map { entity -> entity.toUiModel() }) }
            }
        }

        viewModelScope.launch {
            conversationRepository.observeFolders().collect { folders ->
                _uiState.update { it.copy(folders = folders.map { entity -> entity.toUiModel() }) }
            }
        }
    }

    fun onSelectFolder(folderId: Long?) {
        selectedFolderId.value = folderId
        _uiState.update { it.copy(selectedFolderId = folderId) }
    }

    fun onCreateFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { conversationRepository.createFolder(trimmed) }
    }

    fun onDeleteFolder(folderId: Long) {
        viewModelScope.launch {
            conversationRepository.deleteFolder(folderId)
            // Don't leave the list stuck observing a folder that no longer exists.
            if (selectedFolderId.value == folderId) {
                onSelectFolder(null)
            }
        }
    }

    fun onMoveConversationToFolder(conversationId: Long, folderId: Long?) {
        viewModelScope.launch { conversationRepository.setConversationFolder(conversationId, folderId) }
    }

    fun onDeleteConversation(id: Long) {
        viewModelScope.launch {
            conversationRepository.deleteConversation(id)
        }
    }

    private fun ConversationEntity.toUiModel() =
        ConversationUiModel(id = id, title = title, updatedAt = updatedAt, folderId = folderId)

    private fun FolderEntity.toUiModel() = FolderUiModel(id = id, name = name)
}
