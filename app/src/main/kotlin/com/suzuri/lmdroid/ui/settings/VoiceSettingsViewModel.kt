package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.repository.ApiProfileRepository
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings → 音声: which registered VOICEVOX-compatible profile (API設定,
 * ApiProfileEntity.PROVIDER_VOICEVOX_COMPATIBLE) reads the assistant overlay's replies aloud (see
 * AssistSpeechPlayer) — or, when none is selected, this device's own built-in text-to-speech.
 * Mirrors WebSearchSettingsViewModel's profile-selector pattern, but simpler: speech is always on
 * in the assistant overlay (just via a different backend), so there's no on/off toggle and nothing
 * else to Save — a tap takes effect immediately.
 */
class VoiceSettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val apiProfileRepository: ApiProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VoiceSettingsUiState())
    val uiState: StateFlow<VoiceSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                apiProfileRepository.observeProfiles(),
                settingsRepository.selectedTtsProfileId,
            ) { profiles, selectedId ->
                profiles
                    .filter { it.providerType == ApiProfileEntity.PROVIDER_VOICEVOX_COMPATIBLE }
                    .map { VoiceProfileOptionUiModel(id = it.id, name = it.name) } to selectedId
            }.collect { (profiles, selectedId) ->
                _uiState.update { it.copy(profiles = profiles, selectedProfileId = selectedId) }
            }
        }
    }

    /** This device's own built-in text-to-speech — always available, unlike any one specific profile which might be unreachable. */
    fun onSelectOnDevice() {
        viewModelScope.launch { settingsRepository.setSelectedTtsProfileId(null) }
    }

    fun onSelectProfile(id: Long) {
        viewModelScope.launch { settingsRepository.setSelectedTtsProfileId(id) }
    }
}
