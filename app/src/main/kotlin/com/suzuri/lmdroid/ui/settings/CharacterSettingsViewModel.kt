package com.suzuri.lmdroid.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.character.CharacterModelStore
import com.suzuri.lmdroid.data.character.CharacterModelType
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings → キャラクター: which character the assistant overlay's novel-game stage shows
 * (see AssistScreen's AssistStage). Assets picked via SAF are copied into internal storage by
 * [CharacterModelStore] first — only the stored copy's path ever lands in the settings.
 * LIVE2D/MMD aren't selectable yet (their renderers are later phases of the character plan);
 * the screen disables those options, so this never receives them.
 */
class CharacterSettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val characterModelStore: CharacterModelStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CharacterSettingsUiState())
    val uiState: StateFlow<CharacterSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.characterSettings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
    }

    fun onSelectModelType(type: CharacterModelType) {
        viewModelScope.launch { settingsRepository.setCharacterModelType(type) }
    }

    /** Selecting a standing-sprite image also flips the type to STATIC, since picking a file implies wanting to see it. */
    fun onSpriteSelected(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(importing = true) }
            val path = characterModelStore.importImage(uri, CharacterModelStore.SLOT_SPRITE)
            if (path != null) {
                settingsRepository.setCharacterModelPath(path)
                settingsRepository.setCharacterModelType(CharacterModelType.STATIC)
            } else {
                _uiState.update { it.copy(importFailed = true) }
            }
            _uiState.update { it.copy(importing = false) }
        }
    }

    fun onBackgroundSelected(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(importing = true) }
            val path = characterModelStore.importImage(uri, CharacterModelStore.SLOT_BACKGROUND)
            if (path == null) {
                _uiState.update { it.copy(importFailed = true) }
            } else {
                settingsRepository.setCharacterBackgroundPath(path)
            }
            _uiState.update { it.copy(importing = false) }
        }
    }

    fun onClearSprite() {
        viewModelScope.launch {
            characterModelStore.clear(CharacterModelStore.SLOT_SPRITE)
            settingsRepository.setCharacterModelPath(null)
        }
    }

    fun onClearBackground() {
        viewModelScope.launch {
            characterModelStore.clear(CharacterModelStore.SLOT_BACKGROUND)
            settingsRepository.setCharacterBackgroundPath(null)
        }
    }

    fun onScaleChanged(scale: Float) {
        viewModelScope.launch { settingsRepository.setCharacterScale(scale) }
    }

    fun onToggleTypewriter(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCharacterTypewriterEnabled(enabled) }
    }

    fun onToggleLipSync(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCharacterLipSyncEnabled(enabled) }
    }

    fun onImportFailedShown() {
        _uiState.update { it.copy(importFailed = false) }
    }
}
