package com.suzuri.lmdroid.ui.settings

data class VoicevoxProfileEditUiState(
    val profileName: String = "",
    val baseUrl: String = "",
    // Raw digits only, mirroring WebSearchSettingsUiState.maxToolRounds — kept as a String so the
    // field can hold whatever the user is mid-typing before it's parsed back to an Int on save.
    val speakerId: String = "",
    val testState: TestConnectionState = TestConnectionState.Idle,
    val saved: Boolean = false,
)
