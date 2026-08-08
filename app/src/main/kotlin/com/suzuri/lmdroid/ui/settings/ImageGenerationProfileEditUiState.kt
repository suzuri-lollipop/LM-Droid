package com.suzuri.lmdroid.ui.settings

enum class LocalModelMode {
    FILE,
    URL
}

data class ImageGenerationProfileEditUiState(
    val profileName: String = "",
    val apiKey: String = "",
    val baseUrl: String = "",
    val providerType: String = "",
    val isKeyVisible: Boolean = false,
    val localModelMode: LocalModelMode = LocalModelMode.URL,
    val imageWidth: String = "",
    val imageHeight: String = "",
    val testState: TestConnectionState = TestConnectionState.Idle,
    val saved: Boolean = false,
)
