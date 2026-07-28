package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.repository.ApiProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ApiProfileListViewModel(
    private val apiProfileRepository: ApiProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ApiProfileListUiState())
    val uiState: StateFlow<ApiProfileListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            apiProfileRepository.observeProfiles().collect { profiles ->
                _uiState.update { it.copy(profiles = profiles.map { profile -> profile.toUiModel() }) }
            }
        }
    }

    /** Creates the profile row immediately (so the caller can navigate straight to editing it) and returns its new id. */
    suspend fun createProfile(name: String, providerType: String): Long =
        apiProfileRepository.createProfile(name, providerType)

    /** Several profiles can be enabled at once — each one's registered models then show up in the chat/system model pickers. */
    fun onToggleEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { apiProfileRepository.setEnabled(id, enabled) }
    }

    fun onDeleteProfile(id: Long) {
        viewModelScope.launch { apiProfileRepository.deleteProfile(id) }
    }

    private fun ApiProfileEntity.toUiModel() =
        ApiProfileUiModel(id = id, name = name, providerType = providerType, baseUrl = baseUrl, enabled = enabled)
}
