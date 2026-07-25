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
    val model: String = AppSettings.DEFAULT_MODEL,
    val baseUrl: String = AppSettings.DEFAULT_BASE_URL,
    val isKeyVisible: Boolean = false,
    val testState: TestConnectionState = TestConnectionState.Idle,
    val saved: Boolean = false,
)
