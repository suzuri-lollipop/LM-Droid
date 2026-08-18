package com.suzuri.lmdroid.ui.settings

/** One captured diagnostic WAV (see DeveloperSettingsScreen) — just enough to list and delete. */
data class SttCaptureFileUiModel(
    val name: String,
    val sizeBytes: Long,
)

data class DeveloperSettingsUiState(
    // Bluetooth routing strategy for the local voice-input engine — "auto" (explicit BT routing:
    // communication device + VOICE_COMMUNICATION, the current default) or "disabled" (plain
    // VOICE_RECOGNITION capture, the pre-BT-support behavior). See
    // SettingsRepository.bluetoothRoutingMode.
    val bluetoothRoutingMode: String = "auto",
    // Whether to dump the raw captured mic audio to a WAV for inspection (see SttCaptureStore).
    val sttCaptureDebug: Boolean = false,
    // Captured WAVs currently on disk, refreshed on demand (see DeveloperSettingsViewModel).
    val captureFiles: List<SttCaptureFileUiModel> = emptyList(),
)
