package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.messaging.DeviceMessageController
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings → メッセージ: the on/off toggle for the "send_message" tool (see ConversationRepository,
 * DeviceMessageController) plus which installed messaging app (if any) it should target directly —
 * leaving none selected shows the system share chooser every time the tool is called.
 */
class MessagingSettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val deviceMessageController: DeviceMessageController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MessagingSettingsUiState())
    val uiState: StateFlow<MessagingSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val enabled = settingsRepository.currentMessagingToolEnabled()
            val selected = settingsRepository.currentPreferredMessagingAppPackage()
            val apps = deviceMessageController.installedMessagingApps()
                .map { MessagingAppOptionUiModel(packageName = it.packageName, label = it.label) }
            _uiState.update { it.copy(enabled = enabled, apps = apps, selectedPackageName = selected) }
        }
    }

    fun onEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(enabled = enabled) }
        viewModelScope.launch { settingsRepository.setMessagingToolEnabled(enabled) }
    }

    /** At most one app is preferred at a time — tapping the already-selected one deselects it (falls back to the share chooser). */
    fun onSelectApp(packageName: String) {
        val alreadySelected = _uiState.value.selectedPackageName == packageName
        val newSelection = if (alreadySelected) null else packageName
        _uiState.update { it.copy(selectedPackageName = newSelection) }
        viewModelScope.launch { settingsRepository.setPreferredMessagingAppPackage(newSelection) }
    }
}
