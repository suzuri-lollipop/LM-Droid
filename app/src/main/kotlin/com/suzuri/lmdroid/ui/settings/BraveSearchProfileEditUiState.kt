package com.suzuri.lmdroid.ui.settings

data class BraveSearchProfileEditUiState(
    val profileName: String = "",
    val apiKey: String = "",
    val isKeyVisible: Boolean = false,
    val testState: TestConnectionState = TestConnectionState.Idle,
    val saved: Boolean = false,
)
