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
 * Settings → 位置情報: the on/off toggle for the "get_current_location" tool (see
 * ConversationRepository) — when on, the model is offered the tool and decides for itself whether
 * to call it. Its own category (not folded into Web検索) since it's an unrelated capability with
 * its own runtime permission, not a web-search concern.
 */
class LocationSettingsViewModel(private val settingsRepository: SettingsRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(LocationSettingsUiState())
    val uiState: StateFlow<LocationSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(enabled = settingsRepository.currentLocationEnabled()) }
        }
    }

    /** The caller (LocationSettingsScreen) only ever passes true once location permission is actually granted — this just persists whatever it decides. */
    fun onEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(enabled = enabled) }
        viewModelScope.launch { settingsRepository.setLocationEnabled(enabled) }
    }
}
