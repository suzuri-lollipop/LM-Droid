package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.settings.SettingsRepository
import com.suzuri.lmdroid.data.websearch.BraveSearchClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings → Web検索: the on/off toggle and API key for the "web_search" tool (see
 * ConversationRepository) — when on, the model is offered the tool and decides for itself whether
 * to call it; the app never searches unconditionally. [maxToolRounds] caps how many times one
 * reply can round-trip through the tool before it's forced to answer with what it has, in case
 * the model won't stop calling it — blank/0 means no user-configured cap.
 */
class WebSearchSettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val braveSearchClient: BraveSearchClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WebSearchSettingsUiState())
    val uiState: StateFlow<WebSearchSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val enabled = settingsRepository.currentBraveSearchEnabled()
            val apiKey = settingsRepository.currentBraveSearchApiKey().orEmpty()
            val maxRounds = settingsRepository.currentWebSearchMaxToolRounds()
            _uiState.update {
                it.copy(enabled = enabled, apiKey = apiKey, maxToolRounds = if (maxRounds <= 0) "" else maxRounds.toString())
            }
        }
    }

    /** Takes effect immediately (not gated behind Save) since it's a plain on/off, not a credential. */
    fun onEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(enabled = enabled) }
        viewModelScope.launch { settingsRepository.setBraveSearchEnabled(enabled) }
    }

    fun onApiKeyChange(value: String) {
        _uiState.update { it.copy(apiKey = value, saved = false, testState = TestConnectionState.Idle) }
    }

    fun onToggleKeyVisibility() {
        _uiState.update { it.copy(isKeyVisible = !it.isKeyVisible) }
    }

    /** Digits only, so the field can't hold anything that wouldn't parse — blank stays blank (meaning "unlimited"). */
    fun onMaxToolRoundsChange(value: String) {
        _uiState.update { it.copy(maxToolRounds = value.filter(Char::isDigit), saved = false) }
    }

    fun onSave() {
        val apiKey = _uiState.value.apiKey
        val maxRounds = _uiState.value.maxToolRounds.toIntOrNull() ?: 0
        viewModelScope.launch {
            settingsRepository.setBraveSearchApiKey(apiKey)
            settingsRepository.setWebSearchMaxToolRounds(maxRounds)
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
