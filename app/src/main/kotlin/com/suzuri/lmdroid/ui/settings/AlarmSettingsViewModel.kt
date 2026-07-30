package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings → アラーム・タイマー: the on/off toggle for the "set_alarm"/"set_timer" tools (see
 * ConversationRepository) — when on, the model is offered them and decides for itself whether to
 * call one. No runtime permission to request here (unlike 位置情報): DeviceAlarmController only
 * needs the normal, install-time SET_ALARM permission.
 */
class AlarmSettingsViewModel(private val settingsRepository: SettingsRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AlarmSettingsUiState())
    val uiState: StateFlow<AlarmSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(enabled = settingsRepository.currentAlarmToolEnabled()) }
        }
    }

    fun onEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(enabled = enabled) }
        viewModelScope.launch { settingsRepository.setAlarmToolEnabled(enabled) }
    }
}
