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

/**
 * Settings → Web検索: the on/off toggle for the "web_search"/"fetch_webpage" tools (see
 * ConversationRepository) plus which registered Brave Search profile (see API設定,
 * ApiProfileEntity.PROVIDER_BRAVE_SEARCH) supplies the API key — credentials themselves are
 * managed there, not here, the same way chat model selection points at an API profile without
 * re-exposing its key on the chat screen. [maxToolRounds] caps how many times one reply can
 * round-trip through the tool before it's forced to answer with what it has, in case the model
 * won't stop calling it — blank/0 means no user-configured cap.
 */
class WebSearchSettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val apiProfileRepository: ApiProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WebSearchSettingsUiState())
    val uiState: StateFlow<WebSearchSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val enabled = settingsRepository.currentBraveSearchEnabled()
            val maxRounds = settingsRepository.currentWebSearchMaxToolRounds()
            _uiState.update { it.copy(enabled = enabled, maxToolRounds = if (maxRounds <= 0) "" else maxRounds.toString()) }
        }

        viewModelScope.launch {
            combine(
                apiProfileRepository.observeProfiles(),
                settingsRepository.selectedWebSearchProfileId,
            ) { profiles, selectedId ->
                profiles
                    .filter { it.providerType == ApiProfileEntity.PROVIDER_BRAVE_SEARCH }
                    .map { WebSearchProfileOptionUiModel(id = it.id, name = it.name) } to selectedId
            }.collect { (profiles, selectedId) ->
                _uiState.update { it.copy(profiles = profiles, selectedProfileId = selectedId) }
            }
        }
    }

    /** Takes effect immediately (not gated behind Save) since it's a plain on/off, not a credential. */
    fun onEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(enabled = enabled) }
        viewModelScope.launch { settingsRepository.setBraveSearchEnabled(enabled) }
    }

    /** At most one profile is active at a time — tapping the already-active one deselects it (no web search backend configured at all). */
    fun onSelectProfile(id: Long) {
        val alreadySelected = _uiState.value.selectedProfileId == id
        viewModelScope.launch { settingsRepository.setSelectedWebSearchProfileId(if (alreadySelected) null else id) }
    }

    /** Digits only, so the field can't hold anything that wouldn't parse — blank stays blank (meaning "unlimited"). */
    fun onMaxToolRoundsChange(value: String) {
        _uiState.update { it.copy(maxToolRounds = value.filter(Char::isDigit), saved = false) }
    }

    fun onSave() {
        val maxRounds = _uiState.value.maxToolRounds.toIntOrNull() ?: 0
        viewModelScope.launch {
            settingsRepository.setWebSearchMaxToolRounds(maxRounds)
            _uiState.update { it.copy(saved = true) }
        }
    }
}
