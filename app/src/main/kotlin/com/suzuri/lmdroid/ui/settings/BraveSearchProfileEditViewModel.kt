package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.repository.ApiProfileRepository
import com.suzuri.lmdroid.data.websearch.BraveSearchClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Edits a single [com.suzuri.lmdroid.data.db.ApiProfileEntity] whose providerType is
 * PROVIDER_BRAVE_SEARCH — loaded on demand via [loadProfile], shared across every such profile
 * edited during the app's lifetime, mirroring [SettingsViewModel]'s pattern for OpenAI-compatible
 * profiles. Unlike that one, there's no base URL to edit (Brave Search has exactly one real
 * endpoint — see [BraveSearchClient.DEFAULT_BASE_URL], stored as-is) and no registered-models list.
 */
class BraveSearchProfileEditViewModel(
    private val apiProfileRepository: ApiProfileRepository,
    private val braveSearchClient: BraveSearchClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BraveSearchProfileEditUiState())
    val uiState: StateFlow<BraveSearchProfileEditUiState> = _uiState.asStateFlow()

    private var profileId: Long? = null

    /** Called by the edit screen (e.g. from a LaunchedEffect keyed on the navigated-to profile id). */
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

    fun onApiKeyChange(value: String) {
        _uiState.update { it.copy(apiKey = value, saved = false, testState = TestConnectionState.Idle) }
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
                baseUrl = BraveSearchClient.DEFAULT_BASE_URL,
            )
            _uiState.update { it.copy(saved = true) }
        }
    }

    fun onTestConnection() {
        val apiKey = _uiState.value.apiKey
        if (apiKey.isBlank()) {
            _uiState.update { it.copy(testState = TestConnectionState.Failure("APIキーを入力してください。")) }
            return
        }
        _uiState.update { it.copy(testState = TestConnectionState.Testing) }
        viewModelScope.launch {
            val result = braveSearchClient.search(apiKey, "test")
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
