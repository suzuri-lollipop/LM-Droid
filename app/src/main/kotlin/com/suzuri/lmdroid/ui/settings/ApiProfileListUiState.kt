package com.suzuri.lmdroid.ui.settings

data class ApiProfileUiModel(
    val id: Long,
    val name: String,
    val providerType: String,
    val baseUrl: String,
    val enabled: Boolean,
)

data class ApiProfileListUiState(
    val profiles: List<ApiProfileUiModel> = emptyList(),
)
