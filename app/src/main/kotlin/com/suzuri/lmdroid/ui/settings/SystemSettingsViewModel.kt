package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.ModelOptionRow
import com.suzuri.lmdroid.data.repository.ApiProfileRepository
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings → システム: picks which (profile, model) pair background tasks (auto-titling, prompt
 * suggestions) use, independently of whatever's active for chat. Leaving nothing selected here
 * falls back to the chat selection — see [SettingsRepository.systemSettings].
 */
class SystemSettingsViewModel(
    private val apiProfileRepository: ApiProfileRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SystemSettingsUiState())
    val uiState: StateFlow<SystemSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                apiProfileRepository.observeEnabledModelOptions(),
                settingsRepository.selectedSystemModel,
            ) { options, selected -> options to selected }
                .collect { (options, selected) ->
                    _uiState.update { it.copy(availableModels = options, selectedModel = selected) }
                }
        }
    }

    fun onSelectModel(option: ModelOptionRow) {
        viewModelScope.launch { settingsRepository.setSelectedSystemModel(option.profileId, option.modelId) }
    }

    /** Clears the override so system tasks fall back to whatever's selected for chat again. */
    fun onUseChatModel() {
        viewModelScope.launch { settingsRepository.setSelectedSystemModel(null, null) }
    }
}
