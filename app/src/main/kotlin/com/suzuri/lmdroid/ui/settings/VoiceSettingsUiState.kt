package com.suzuri.lmdroid.ui.settings

import com.suzuri.lmdroid.data.stt.SpeechModel

/** One registered VOICEVOX-compatible profile (see ApiProfileEntity.PROVIDER_VOICEVOX_COMPATIBLE), as offered by the 音声 screen's backend selector. */
data class VoiceProfileOptionUiModel(
    val id: Long,
    val name: String,
)

data class VoiceSettingsUiState(
    // Every registered VOICEVOX-compatible profile, and which one (if any) is currently active —
    // see SettingsRepository.selectedTtsProfileId. Null selection means this device's own built-in
    // text-to-speech, which is always available and is the default, unlike Web検索's "none" state.
    val profiles: List<VoiceProfileOptionUiModel> = emptyList(),
    val selectedProfileId: Long? = null,

    // Speech recognition (STT) models
    val sttModels: List<SpeechModelUiModel> = emptyList(),
    val selectedSttModelId: String = "vosk-jp-small",
    val downloadError: String? = null,

    // The language Whisper listens for (see SettingsRepository.selectedSttLanguage) — "ja", "en" or "auto".
    val selectedSttLanguage: String = "ja",

    // Whether mic-driven dictation (chat composer + assistant overlay) uses the on-device
    // Vosk/Whisper pipeline above, or the OS's default (usually Google) recognizer — see
    // SettingsRepository.voiceInputUseLocalEngine.
    val voiceInputUseLocalEngine: Boolean = true,

    // How many times the local engine retries connecting Bluetooth SCO audio before falling back
    // to the phone's own mic/speaker — see SettingsRepository.bluetoothScoMaxAttempts.
    val bluetoothScoMaxAttempts: Int = 3,
)

data class SpeechModelUiModel(
    val model: SpeechModel,
    val isAvailable: Boolean,
    val isDownloading: Boolean = false,
    val progress: Float = 0f
)
