package com.suzuri.lmdroid.ui.settings

data class SystemPromptUiModel(
    val id: Long,
    val name: String,
    val content: String,
    val isSelected: Boolean,
)

data class SystemPromptListUiState(
    val prompts: List<SystemPromptUiModel> = emptyList(),
)
