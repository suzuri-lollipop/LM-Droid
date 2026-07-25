package com.suzuri.lmdroid.ui.history

data class FolderUiModel(
    val id: Long,
    val name: String,
)

data class ConversationUiModel(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val folderId: Long? = null,
)

data class HistoryUiState(
    val conversations: List<ConversationUiModel> = emptyList(),
    val folders: List<FolderUiModel> = emptyList(),
    // null means "all conversations" (no folder filter selected).
    val selectedFolderId: Long? = null,
)
