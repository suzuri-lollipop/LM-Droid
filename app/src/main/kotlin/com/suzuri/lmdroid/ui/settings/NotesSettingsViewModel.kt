package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.notes.DeviceNoteController
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings → メモ: the on/off toggle for the "create_note" tool (see ConversationRepository,
 * DeviceNoteController) plus which installed note-taking app (if any) it should target directly —
 * leaving none selected shows the system share chooser every time the tool is called.
 */
class NotesSettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val deviceNoteController: DeviceNoteController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotesSettingsUiState())
    val uiState: StateFlow<NotesSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val enabled = settingsRepository.currentNotesToolEnabled()
            val selected = settingsRepository.currentPreferredNoteAppPackage()
            val apps = deviceNoteController.installedNoteApps()
                .map { NoteAppOptionUiModel(packageName = it.packageName, label = it.label) }
            _uiState.update { it.copy(enabled = enabled, apps = apps, selectedPackageName = selected) }
        }
    }

    fun onEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(enabled = enabled) }
        viewModelScope.launch { settingsRepository.setNotesToolEnabled(enabled) }
    }

    /** At most one app is preferred at a time — tapping the already-selected one deselects it (falls back to the share chooser). */
    fun onSelectApp(packageName: String) {
        val alreadySelected = _uiState.value.selectedPackageName == packageName
        val newSelection = if (alreadySelected) null else packageName
        _uiState.update { it.copy(selectedPackageName = newSelection) }
        viewModelScope.launch { settingsRepository.setPreferredNoteAppPackage(newSelection) }
    }
}
