package com.suzuri.lmdroid.ui.settings

import com.suzuri.lmdroid.data.settings.AppSettings

sealed class TestConnectionState {
    object Idle : TestConnectionState()
    object Testing : TestConnectionState()
    object Success : TestConnectionState()
    data class Failure(val message: String) : TestConnectionState()
}

data class SettingsUiState(
    val profileName: String = "",
    val apiKey: String = "",
    val baseUrl: String = AppSettings.DEFAULT_BASE_URL,
    val isKeyVisible: Boolean = false,
    val testState: TestConnectionState = TestConnectionState.Idle,
    // Auto-fetched from the provider's /models endpoint via a successful "接続テスト" — never
    // typed in by hand.
    val models: List<String> = emptyList(),
    val saved: Boolean = false,
)
