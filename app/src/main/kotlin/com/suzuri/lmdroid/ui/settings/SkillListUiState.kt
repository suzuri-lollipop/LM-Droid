package com.suzuri.lmdroid.ui.settings

data class SkillUiModel(
    val id: Long,
    val name: String,
    val description: String,
    val isSelected: Boolean,
)

data class SkillListUiState(
    val skills: List<SkillUiModel> = emptyList(),
)
