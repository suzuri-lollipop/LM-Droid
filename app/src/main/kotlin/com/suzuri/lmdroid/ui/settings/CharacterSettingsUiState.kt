package com.suzuri.lmdroid.ui.settings

import com.suzuri.lmdroid.data.character.CharacterSettings

/**
 * Settings → キャラクター. [importFailed] is a one-shot flag the screen consumes to show a
 * toast when an SAF import couldn't be copied into internal storage.
 */
data class CharacterSettingsUiState(
    val settings: CharacterSettings = CharacterSettings(),
    val importing: Boolean = false,
    val importFailed: Boolean = false,
)
