package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.ModelOptionRow
import com.suzuri.lmdroid.data.repository.ApiProfileRepository
import com.suzuri.lmdroid.data.settings.AssistantToolLaunchTiming
import com.suzuri.lmdroid.data.settings.SettingsRepository
import com.suzuri.lmdroid.service.WakeWordDebugManager
import com.suzuri.lmdroid.service.WakeWordStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings → アシスタント: picks which (profile, model) pair the assistant overlay (AssistViewModel)
 * uses, independently of whatever's active for chat. Leaving nothing selected here falls back to
 * the chat selection — see [SettingsRepository.assistantSettings] — mirrors
 * [SystemSettingsViewModel]'s identical pattern for background "system" tasks.
 *
 * Also manages the background "wake word" listener (WakeWordService) settings.
 */
class AssistantSettingsViewModel(
    private val apiProfileRepository: ApiProfileRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AssistantSettingsUiState())
    val uiState: StateFlow<AssistantSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                apiProfileRepository.observeEnabledModelOptions(),
                settingsRepository.selectedAssistantModel,
            ) { options, selected -> options to selected }
                .collect { (options, selected) ->
                    _uiState.update { it.copy(availableModels = options, selectedModel = selected) }
                }
        }
        viewModelScope.launch {
            settingsRepository.assistantToolLaunchTiming.collect { timing ->
                _uiState.update { it.copy(toolLaunchTiming = timing) }
            }
        }
        viewModelScope.launch {
            combine(
                settingsRepository.wakeWordEnabled,
                settingsRepository.wakeWord,
                WakeWordDebugManager.status,
                WakeWordDebugManager.lastResult,
            ) { enabled, word, status, result -> 
                AssistantSettingsWakeWordState(enabled, word, status, result)
            }.collect { state ->
                _uiState.update { it.copy(
                    wakeWordEnabled = state.enabled,
                    wakeWord = state.word,
                    wakeWordStatus = state.status,
                    wakeWordLastResult = state.lastResult
                ) }
            }
        }
    }

    fun onSelectModel(option: ModelOptionRow) {
        viewModelScope.launch { settingsRepository.setSelectedAssistantModel(option.profileId, option.modelId) }
    }

    /** Clears the override so the assistant overlay falls back to whatever's selected for chat again. */
    fun onUseChatModel() {
        viewModelScope.launch { settingsRepository.setSelectedAssistantModel(null, null) }
    }

    fun onSelectToolLaunchTiming(timing: AssistantToolLaunchTiming) {
        viewModelScope.launch { settingsRepository.setAssistantToolLaunchTiming(timing) }
    }

    fun onToggleWakeWord(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setWakeWordEnabled(enabled) }
    }

    fun onUpdateWakeWord(word: String) {
        viewModelScope.launch { settingsRepository.setWakeWord(word) }
    }
}

private data class AssistantSettingsWakeWordState(
    val enabled: Boolean,
    val word: String,
    val status: WakeWordStatus,
    val lastResult: String,
)
