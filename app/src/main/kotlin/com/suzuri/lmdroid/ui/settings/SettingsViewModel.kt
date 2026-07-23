package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.network.OpenAiApiClient
import com.suzuri.lmdroid.data.network.OpenAiException
import com.suzuri.lmdroid.data.settings.AppSettings
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val openAiApiClient: OpenAiApiClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsRepository.currentSettings()
            _uiState.update { it.copy(apiKey = settings.apiKey.orEmpty(), model = settings.model) }
        }
    }

    fun onApiKeyChange(value: String) {
        _uiState.update { it.copy(apiKey = value, saved = false, testState = TestConnectionState.Idle) }
    }

    fun onModelChange(value: String) {
        _uiState.update { it.copy(model = value, saved = false) }
    }

    fun onToggleKeyVisibility() {
        _uiState.update { it.copy(isKeyVisible = !it.isKeyVisible) }
    }

    fun onSave() {
        val state = _uiState.value
        viewModelScope.launch {
            if (state.apiKey.isNotBlank()) {
                settingsRepository.saveApiKey(state.apiKey)
            }
            settingsRepository.saveModel(state.model.ifBlank { AppSettings.DEFAULT_MODEL })
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
            val result = openAiApiClient.testApiKey(apiKey)
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
