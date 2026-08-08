package com.suzuri.lmdroid.ui.settings

data class ImageGenerationProfileEditUiState(
    val profileName: String = "",
    val apiKey: String = "",
    val baseUrl: String = "",
    val providerType: String = "",
    val isKeyVisible: Boolean = false,
    val testState: TestConnectionState = TestConnectionState.Idle,
    val saved: Boolean = false,
)
