package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.network.OpenAiApiClient
import com.suzuri.lmdroid.data.network.OpenAiException
import com.suzuri.lmdroid.data.repository.ApiProfileRepository
import com.suzuri.lmdroid.data.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Edits a single [com.suzuri.lmdroid.data.db.ApiProfileEntity], loaded on demand via [loadProfile]
 * — this ViewModel instance is shared across every profile edited during the app's lifetime
 * (hoisted once in MainActivity), not recreated per profile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val apiProfileRepository: ApiProfileRepository,
    private val openAiApiClient: OpenAiApiClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var profileId: Long? = null

    // A StateFlow (not a plain var) so switching which profile is being edited drives observeModels
    // via flatMapLatest below, which cancels the previous profile's collector — without this, editing
    // profile A and then profile B left BOTH collectors running against the shared uiState.models,
    // and whichever one's Flow happened to re-emit last (e.g. profile A's, on any unrelated write to
    // the api_models table) silently overwrote profile B's just-fetched models with stale ones.
    private val observedProfileId = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            observedProfileId.filterNotNull()
                .flatMapLatest { id -> apiProfileRepository.observeModels(id) }
                .collect { models -> _uiState.update { it.copy(models = models) } }
        }
    }

    /** Called by the edit screen (e.g. from a LaunchedEffect keyed on the navigated-to profile id). */
    fun loadProfile(id: Long) {
        if (profileId == id) return
        profileId = id
        observedProfileId.value = id
        viewModelScope.launch {
            val profile = apiProfileRepository.getProfile(id) ?: return@launch
            val apiKey = apiProfileRepository.decryptApiKey(profile)
            _uiState.update {
                it.copy(
                    profileName = profile.name,
                    apiKey = apiKey.orEmpty(),
                    baseUrl = profile.baseUrl,
                    isKeyVisible = false,
                    testState = TestConnectionState.Idle,
                    saved = false,
                    defaultThinkingEnabled = profile.defaultThinkingEnabled,
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

    fun onBaseUrlChange(value: String) {
        _uiState.update { it.copy(baseUrl = value, saved = false, testState = TestConnectionState.Idle) }
    }

    fun onToggleKeyVisibility() {
        _uiState.update { it.copy(isKeyVisible = !it.isKeyVisible) }
    }

    /** null = サーバー既定 (no configured default) — see ApiProfileEntity.defaultThinkingEnabled. */
    fun onDefaultThinkingEnabledChange(value: Boolean?) {
        _uiState.update { it.copy(defaultThinkingEnabled = value, saved = false) }
    }

    fun onSave() {
        val id = profileId ?: return
        val state = _uiState.value
        val apiKey = state.apiKey
        val baseUrl = state.baseUrl.ifBlank { AppSettings.DEFAULT_BASE_URL }
        viewModelScope.launch {
            apiProfileRepository.updateProfile(
                id = id,
                name = state.profileName,
                apiKey = apiKey,
                baseUrl = baseUrl,
                defaultThinkingEnabled = state.defaultThinkingEnabled,
            )
            _uiState.update { it.copy(saved = true) }

            // Saving a key is also how its models get registered — without this, a profile with
            // a saved key but no models never shows up as usable in the chat screen's model
            // switcher unless the user separately remembers to also tap "接続テスト".
            if (apiKey.isNotBlank()) {
                val result = apiProfileRepository.refreshModels(id, apiKey, baseUrl)
                _uiState.update {
                    it.copy(
                        testState = result.fold(
                            onSuccess = { TestConnectionState.Success },
                            onFailure = { e ->
                                TestConnectionState.Failure((e as? OpenAiException)?.userMessage ?: "接続に失敗しました。")
                            },
                        ),
                    )
                }
            }
        }
    }

    fun onTestConnection() {
        val id = profileId
        val apiKey = _uiState.value.apiKey
        if (apiKey.isBlank()) {
            _uiState.update { it.copy(testState = TestConnectionState.Failure("APIキーを入力してください。")) }
            return
        }
        _uiState.update { it.copy(testState = TestConnectionState.Testing) }
        val baseUrl = _uiState.value.baseUrl.ifBlank { AppSettings.DEFAULT_BASE_URL }
        viewModelScope.launch {
            val result = openAiApiClient.testApiKey(apiKey, baseUrl)
            _uiState.update {
                it.copy(
                    testState = result.fold(
                        onSuccess = { TestConnectionState.Success },
                        onFailure = { e ->
                            TestConnectionState.Failure((e as? OpenAiException)?.userMessage ?: "接続に失敗しました。")
                        },
                    ),
                )
            }
            // A successful connection test is also how a profile's available models get
            // (re-)registered — models are never typed in by hand.
            if (result.isSuccess && id != null) {
                apiProfileRepository.refreshModels(id, apiKey, baseUrl)
            }
        }
    }
}
