package com.suzuri.lmdroid.ui.settings

/** One installed app capable of receiving the "create_note" tool's ACTION_SEND intent, as offered by the メモ screen's app picker (see DeviceNoteController.installedNoteApps). */
data class NoteAppOptionUiModel(
    val packageName: String,
    val label: String,
)

data class NotesSettingsUiState(
    val enabled: Boolean = false,
    // Every installed app that can receive a plain-text share, and which one (if any) is the
    // preferred target — see SettingsRepository.preferredNoteAppPackage. At most one is preferred
    // at a time; none selected means the system share chooser is shown each time.
    val apps: List<NoteAppOptionUiModel> = emptyList(),
    val selectedPackageName: String? = null,
)
