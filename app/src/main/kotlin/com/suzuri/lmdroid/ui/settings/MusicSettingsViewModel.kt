package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.music.DeviceMusicController
import com.suzuri.lmdroid.data.repository.ApiProfileRepository
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings → 音楽: the on/off toggle for the "play_music" tool (see ConversationRepository,
 * DeviceMusicController) plus which installed music app (if any) it should target directly —
 * leaving none selected lets the system resolve it itself. Separately, which registered YouTube
 * Data API profile (see API設定, ApiProfileEntity.PROVIDER_YOUTUBE_DATA_API) is active — credentials
 * themselves are managed there, not here, the same way Web検索 points at a Brave Search profile.
 * Configuring one lets play_music resolve a query to a specific YouTube Music track and actually
 * start playback there, instead of only opening YouTube Music's search screen.
 */
class MusicSettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val deviceMusicController: DeviceMusicController,
    private val apiProfileRepository: ApiProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MusicSettingsUiState())
    val uiState: StateFlow<MusicSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val enabled = settingsRepository.currentMusicToolEnabled()
            val selected = settingsRepository.currentPreferredMusicAppPackage()
            val apps = deviceMusicController.installedMusicApps()
                .map { MusicAppOptionUiModel(packageName = it.packageName, label = it.label) }
            _uiState.update { it.copy(enabled = enabled, apps = apps, selectedPackageName = selected) }
        }

        viewModelScope.launch {
            combine(
                apiProfileRepository.observeProfiles(),
                settingsRepository.selectedYoutubeDataApiProfileId,
            ) { profiles, selectedId ->
                profiles
                    .filter { it.providerType == ApiProfileEntity.PROVIDER_YOUTUBE_DATA_API }
                    .map { YoutubeDataApiProfileOptionUiModel(id = it.id, name = it.name) } to selectedId
            }.collect { (profiles, selectedId) ->
                _uiState.update { it.copy(youtubeApiProfiles = profiles, selectedYoutubeApiProfileId = selectedId) }
            }
        }
    }

    fun onEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(enabled = enabled) }
        viewModelScope.launch { settingsRepository.setMusicToolEnabled(enabled) }
    }

    /** At most one app is preferred at a time — tapping the already-selected one deselects it (falls back to the system's own resolution). */
    fun onSelectApp(packageName: String) {
        val alreadySelected = _uiState.value.selectedPackageName == packageName
        val newSelection = if (alreadySelected) null else packageName
        _uiState.update { it.copy(selectedPackageName = newSelection) }
        viewModelScope.launch { settingsRepository.setPreferredMusicAppPackage(newSelection) }
    }

    /** At most one YouTube Data API profile is active at a time — tapping the already-active one deselects it (play_music falls back to the generic media-search intent). */
    fun onSelectYoutubeApiProfile(id: Long) {
        val alreadySelected = _uiState.value.selectedYoutubeApiProfileId == id
        viewModelScope.launch { settingsRepository.setSelectedYoutubeDataApiProfileId(if (alreadySelected) null else id) }
    }
}
