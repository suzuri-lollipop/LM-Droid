package com.suzuri.lmdroid.ui.settings

import com.suzuri.lmdroid.data.db.ModelOptionRow
import com.suzuri.lmdroid.data.settings.AssistantToolLaunchTiming
import com.suzuri.lmdroid.data.settings.SelectedModel

data class AssistantSettingsUiState(
    val availableModels: List<ModelOptionRow> = emptyList(),
    // null means "use whatever's selected for chat" — see SettingsRepository.assistantSettings.
    val selectedModel: SelectedModel? = null,
    val toolLaunchTiming: AssistantToolLaunchTiming = AssistantToolLaunchTiming.WHILE_SPEAKING,
)
