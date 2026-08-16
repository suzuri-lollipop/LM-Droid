package com.suzuri.lmdroid.ui.settings

import com.suzuri.lmdroid.data.audio.DEFAULT_MIC_INPUT_THRESHOLD
import com.suzuri.lmdroid.data.db.ModelOptionRow
import com.suzuri.lmdroid.data.settings.AssistantToolLaunchTiming
import com.suzuri.lmdroid.data.settings.SelectedModel
import com.suzuri.lmdroid.service.WakeWordStatus

data class AssistantSettingsUiState(
    val availableModels: List<ModelOptionRow> = emptyList(),
    // null means "use whatever's selected for chat" — see SettingsRepository.assistantSettings.
    val selectedModel: SelectedModel? = null,
    val toolLaunchTiming: AssistantToolLaunchTiming = AssistantToolLaunchTiming.WHILE_SPEAKING,
    val wakeWordEnabled: Boolean = false,
    val wakeWord: String = "",
    val wakeWordStatus: WakeWordStatus = WakeWordStatus.Idle,
    val wakeWordLastResult: String = "",
    val micInputThreshold: Float = DEFAULT_MIC_INPUT_THRESHOLD,
    val micLevel: Float = 0f,
)
