package com.suzuri.lmdroid.ui.settings

/** One registered Brave Search profile (see ApiProfileEntity.PROVIDER_BRAVE_SEARCH), as offered by the Web検索 screen's profile selector. */
data class WebSearchProfileOptionUiModel(
    val id: Long,
    val name: String,
)

data class WebSearchSettingsUiState(
    val enabled: Boolean = false,
    // Every registered Brave Search profile, and which one (if any) is currently active — see
    // SettingsRepository.selectedWebSearchProfileId. At most one is active at a time.
    val profiles: List<WebSearchProfileOptionUiModel> = emptyList(),
    val selectedProfileId: Long? = null,
    // Raw digits only; blank means "unlimited" (persisted as 0 — see SettingsRepository.webSearchMaxToolRounds).
    val maxToolRounds: String = "",
    val saved: Boolean = false,
)
