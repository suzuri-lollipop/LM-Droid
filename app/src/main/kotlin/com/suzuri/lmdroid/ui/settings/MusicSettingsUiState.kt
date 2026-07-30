package com.suzuri.lmdroid.ui.settings

/** One installed app capable of receiving the "play_music" tool's media-search play request, as offered by the 音楽 screen's app picker (see DeviceMusicController.installedMusicApps). */
data class MusicAppOptionUiModel(
    val packageName: String,
    val label: String,
)

/** One registered YouTube Data API profile (see ApiProfileEntity.PROVIDER_YOUTUBE_DATA_API), as offered by the 音楽 screen's profile selector. */
data class YoutubeDataApiProfileOptionUiModel(
    val id: Long,
    val name: String,
)

data class MusicSettingsUiState(
    val enabled: Boolean = false,
    // Every installed app that can receive a media-search play request, and which one (if any) is
    // the preferred target — see SettingsRepository.preferredMusicAppPackage. At most one is
    // preferred at a time; none selected means the system resolves it itself.
    val apps: List<MusicAppOptionUiModel> = emptyList(),
    val selectedPackageName: String? = null,
    // Every registered YouTube Data API profile, and which one (if any) is active — see
    // SettingsRepository.selectedYoutubeDataApiProfileId. Configuring one lets play_music actually
    // start YouTube Music playback (see DeviceMusicController.prepareOpenYoutubeMusicTrack) instead
    // of just opening its search screen.
    val youtubeApiProfiles: List<YoutubeDataApiProfileOptionUiModel> = emptyList(),
    val selectedYoutubeApiProfileId: Long? = null,
)
