package com.suzuri.lmdroid.ui.settings

data class ApiProfileUiModel(
    val id: Long,
    val name: String,
    val model: String,
)

data class ApiProfileListUiState(
    val profiles: List<ApiProfileUiModel> = emptyList(),
    val selectedProfileId: Long? = null,
)
