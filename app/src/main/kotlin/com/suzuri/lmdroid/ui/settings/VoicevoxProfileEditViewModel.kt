package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.repository.ApiProfileRepository
import com.suzuri.lmdroid.data.tts.VoicevoxCompatibleClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Edits a single [ApiProfileEntity] whose providerType is PROVIDER_VOICEVOX_COMPATIBLE — loaded on
 * demand via [loadProfile], shared across every such profile edited during the app's lifetime,
 * mirroring [BraveSearchProfileEditViewModel]'s pattern. Unlike that one, there's a user-editable
 * base URL (VOICEVOX/AivisSpeech both run as local servers on whichever port the user has them on)
 * and a speaker id instead of an API key (these are unauthenticated local servers).
 */
class VoicevoxProfileEditViewModel(
    private val apiProfileRepository: ApiProfileRepository,
    private val voicevoxCompatibleClient: VoicevoxCompatibleClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VoicevoxProfileEditUiState())
    val uiState: StateFlow<VoicevoxProfileEditUiState> = _uiState.asStateFlow()

    private var profileId: Long? = null

    /** Called by the edit screen (e.g. from a LaunchedEffect keyed on the navigated-to profile id). */
    fun loadProfile(id: Long) {
        if (profileId == id) return
        profileId = id
        viewModelScope.launch {
            val profile = apiProfileRepository.getProfile(id) ?: return@launch
            _uiState.update {
                it.copy(
                    profileName = profile.name,
                    baseUrl = profile.baseUrl,
                    speakerId = (profile.voicevoxSpeakerId ?: ApiProfileEntity.DEFAULT_VOICEVOX_SPEAKER_ID).toString(),
                    testState = TestConnectionState.Idle,
                    saved = false,
                )
            }
        }
    }

    fun onProfileNameChange(value: String) {
        _uiState.update { it.copy(profileName = value, saved = false) }
    }

    fun onBaseUrlChange(value: String) {
        _uiState.update { it.copy(baseUrl = value, saved = false, testState = TestConnectionState.Idle) }
    }

    /** Digits only, so the field can't hold anything that wouldn't parse. */
    fun onSpeakerIdChange(value: String) {
        _uiState.update { it.copy(speakerId = value.filter(Char::isDigit), saved = false) }
    }

    fun onSave() {
        val id = profileId ?: return
        val state = _uiState.value
        val speakerId = state.speakerId.toIntOrNull() ?: ApiProfileEntity.DEFAULT_VOICEVOX_SPEAKER_ID
        viewModelScope.launch {
            apiProfileRepository.updateVoicevoxProfile(
                id = id,
                name = state.profileName,
                baseUrl = state.baseUrl,
                speakerId = speakerId,
            )
            _uiState.update { it.copy(saved = true) }
        }
    }

    fun onTestConnection() {
        val baseUrl = _uiState.value.baseUrl
        if (baseUrl.isBlank()) {
            _uiState.update { it.copy(testState = TestConnectionState.Failure("URLを入力してください。")) }
            return
        }
        _uiState.update { it.copy(testState = TestConnectionState.Testing) }
        viewModelScope.launch {
            val result = voicevoxCompatibleClient.testConnection(baseUrl)
            _uiState.update {
                it.copy(
                    testState = result.fold(
                        onSuccess = { TestConnectionState.Success },
                        onFailure = { TestConnectionState.Failure("接続に失敗しました。") },
                    ),
                )
            }
        }
    }
}
