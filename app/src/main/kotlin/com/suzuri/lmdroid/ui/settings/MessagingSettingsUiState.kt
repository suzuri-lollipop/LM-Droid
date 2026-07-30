package com.suzuri.lmdroid.ui.settings

/** One installed app capable of receiving the "send_message" tool's ACTION_SEND intent, as offered by the メッセージ screen's app picker (see DeviceMessageController.installedMessagingApps). */
data class MessagingAppOptionUiModel(
    val packageName: String,
    val label: String,
)

data class MessagingSettingsUiState(
    val enabled: Boolean = false,
    // Every installed app that can receive a plain-text share, and which one (if any) is the
    // preferred target — see SettingsRepository.preferredMessagingAppPackage. At most one is
    // preferred at a time; none selected means the system share chooser is shown each time.
    val apps: List<MessagingAppOptionUiModel> = emptyList(),
    val selectedPackageName: String? = null,
)
