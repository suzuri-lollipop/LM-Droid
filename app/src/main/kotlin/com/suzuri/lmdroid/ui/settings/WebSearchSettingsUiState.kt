package com.suzuri.lmdroid.ui.settings

data class WebSearchSettingsUiState(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val isKeyVisible: Boolean = false,
    // Raw digits only; blank means "unlimited" (persisted as 0 — see SettingsRepository.webSearchMaxToolRounds).
    val maxToolRounds: String = "",
    val testState: TestConnectionState = TestConnectionState.Idle,
    val saved: Boolean = false,
)
