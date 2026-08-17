package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.repository.ApiProfileRepository
import com.suzuri.lmdroid.data.settings.SettingsRepository
import com.suzuri.lmdroid.data.stt.SpeechModel
import com.suzuri.lmdroid.data.stt.SpeechModelManager
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
    private val speechModelManager: SpeechModelManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VoiceSettingsUiState())
    val uiState: StateFlow<VoiceSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                apiProfileRepository.observeProfiles(),
                settingsRepository.selectedTtsProfileId,
                settingsRepository.selectedSttModelId,
                settingsRepository.selectedSttLanguage,
                settingsRepository.voiceInputUseLocalEngine,
            ) { profiles, selectedTtsId, selectedSttId, selectedSttLanguage, voiceInputUseLocalEngine ->
                val ttsProfiles = profiles
                    .filter {
                        it.providerType == ApiProfileEntity.PROVIDER_VOICEVOX_COMPATIBLE ||
                            it.providerType == ApiProfileEntity.PROVIDER_OPENAI_TTS
                    }
                    .map { VoiceProfileOptionUiModel(id = it.id, name = it.name) }

                _uiState.update { it.copy(
                    profiles = ttsProfiles,
                    selectedProfileId = selectedTtsId,
                    selectedSttModelId = selectedSttId,
                    selectedSttLanguage = selectedSttLanguage,
                    voiceInputUseLocalEngine = voiceInputUseLocalEngine,
                    sttModels = SpeechModel.ALL_MODELS.map { model ->
                        SpeechModelUiModel(
                            model = model,
                            isAvailable = speechModelManager.isModelAvailable(model),
                        )
                    }
                ) }
            }.collect { }
        }
    }

    /** This device's own built-in text-to-speech — always available, unlike any one specific profile which might be unreachable. */
    fun onSelectOnDevice() {
        viewModelScope.launch { settingsRepository.setSelectedTtsProfileId(null) }
    }

    fun onSelectProfile(id: Long) {
        viewModelScope.launch { settingsRepository.setSelectedTtsProfileId(id) }
    }

    fun onSelectSttModel(id: String) {
        viewModelScope.launch { settingsRepository.setSelectedSttModelId(id) }
    }

    fun onSelectSttLanguage(code: String) {
        viewModelScope.launch { settingsRepository.setSelectedSttLanguage(code) }
    }

    fun onSelectVoiceInputEngine(useLocal: Boolean) {
        viewModelScope.launch { settingsRepository.setVoiceInputUseLocalEngine(useLocal) }
    }

    fun onDownloadModel(model: SpeechModel) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    sttModels = state.sttModels.map { 
                        if (it.model.id == model.id) it.copy(isDownloading = true, progress = 0f) else it 
                    },
                    downloadError = null
                )
            }

            val result = speechModelManager.downloadModel(model) { progress ->
                _uiState.update { state ->
                    state.copy(
                        sttModels = state.sttModels.map {
                            if (it.model.id == model.id) it.copy(progress = progress) else it
                        }
                    )
                }
            }

            if (result.isSuccess) {
                _uiState.update { state ->
                    state.copy(
                        sttModels = state.sttModels.map {
                            if (it.model.id == model.id) it.copy(isDownloading = false, isAvailable = true) else it
                        }
                    )
                }
            } else {
                _uiState.update { state ->
                    state.copy(
                        sttModels = state.sttModels.map {
                            if (it.model.id == model.id) it.copy(isDownloading = false) else it
                        },
                        downloadError = result.exceptionOrNull()?.message ?: "Download failed"
                    )
                }
            }
        }
    }

    fun onDeleteModel(model: SpeechModel) {
        speechModelManager.deleteModel(model)
        _uiState.update { state ->
            state.copy(
                sttModels = state.sttModels.map {
                    if (it.model.id == model.id) it.copy(isAvailable = false) else it
                }
            )
        }
    }
}
