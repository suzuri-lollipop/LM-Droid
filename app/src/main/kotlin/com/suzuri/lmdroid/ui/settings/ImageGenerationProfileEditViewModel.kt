package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.repository.ApiProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ImageGenerationProfileEditViewModel(
    private val apiProfileRepository: ApiProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImageGenerationProfileEditUiState())
    val uiState: StateFlow<ImageGenerationProfileEditUiState> = _uiState.asStateFlow()

    private var profileId: Long? = null

    fun loadProfile(id: Long) {
        if (profileId == id) return
        profileId = id
        viewModelScope.launch {
            val profile = apiProfileRepository.getProfile(id) ?: return@launch
            val apiKey = apiProfileRepository.decryptApiKey(profile)
            _uiState.update {
                it.copy(
                    profileName = profile.name,
                    apiKey = apiKey.orEmpty(),
                    baseUrl = profile.baseUrl,
                    providerType = profile.providerType,
                    localModelMode = if (profile.baseUrl.startsWith("content://") || profile.baseUrl.startsWith("/")) {
                        LocalModelMode.FILE
                    } else {
                        LocalModelMode.URL
                    },
                    isKeyVisible = false,
                    testState = TestConnectionState.Idle,
                    saved = false,
                )
            }
        }
    }

    fun onProfileNameChange(value: String) {
        _uiState.update { it.copy(profileName = value, saved = false) }
    }

    fun onLocalModelModeChange(mode: LocalModelMode) {
        _uiState.update { it.copy(localModelMode = mode, saved = false) }
    }

    fun onModelFileSelected(uri: String) {
        _uiState.update { it.copy(baseUrl = uri, saved = false) }
    }

    fun onApiKeyChange(value: String) {
        _uiState.update { it.copy(apiKey = value, saved = false, testState = TestConnectionState.Idle) }
    }

    fun onBaseUrlChange(value: String) {
        _uiState.update { it.copy(baseUrl = value, saved = false, testState = TestConnectionState.Idle) }
    }

    fun onToggleKeyVisibility() {
        _uiState.update { it.copy(isKeyVisible = !it.isKeyVisible) }
    }

    fun onSave() {
        val id = profileId ?: return
        val state = _uiState.value
        viewModelScope.launch {
            apiProfileRepository.updateProfile(
                id = id,
                name = state.profileName,
                apiKey = state.apiKey,
                baseUrl = state.baseUrl,
            )
            _uiState.update { it.copy(saved = true) }
        }
    }
}
