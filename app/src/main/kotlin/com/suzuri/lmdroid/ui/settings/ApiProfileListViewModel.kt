package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.repository.ApiProfileRepository
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ApiProfileListViewModel(
    private val apiProfileRepository: ApiProfileRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ApiProfileListUiState())
    val uiState: StateFlow<ApiProfileListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                apiProfileRepository.observeProfiles(),
                settingsRepository.selectedProfileId,
            ) { profiles, selectedId -> profiles.map { it.toUiModel() } to selectedId }
                .collect { (profiles, selectedId) ->
                    _uiState.update { it.copy(profiles = profiles, selectedProfileId = selectedId) }
                }
        }
    }

    /** Creates the profile row immediately (so the caller can navigate straight to editing it) and returns its new id. */
    suspend fun createProfile(name: String, providerType: String): Long =
        apiProfileRepository.createProfile(name, providerType)

    fun onSelectProfile(id: Long) {
        viewModelScope.launch { settingsRepository.setSelectedProfile(id) }
    }

    fun onDeleteProfile(id: Long) {
        viewModelScope.launch {
            apiProfileRepository.deleteProfile(id)
            // Don't leave the app pointed at a now-deleted profile — this naturally falls back to
            // the "APIキー未登録" state the rest of the app already knows how to show.
            if (settingsRepository.currentSelectedProfileId() == id) {
                settingsRepository.setSelectedProfile(null)
            }
        }
    }

    private fun ApiProfileEntity.toUiModel() = ApiProfileUiModel(id = id, name = name, model = model)
}
