package com.suzuri.lmdroid.ui.history

data class ConversationUiModel(
    val id: Long,
    val title: String,
    val updatedAt: Long,
)

data class HistoryUiState(
    val conversations: List<ConversationUiModel> = emptyList(),
)
