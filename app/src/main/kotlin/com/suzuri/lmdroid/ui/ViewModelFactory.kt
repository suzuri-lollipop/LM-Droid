package com.suzuri.lmdroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.suzuri.lmdroid.AppContainer
import com.suzuri.lmdroid.ui.assist.AssistViewModel
import com.suzuri.lmdroid.ui.chat.ChatViewModel
import com.suzuri.lmdroid.ui.history.HistoryViewModel
import com.suzuri.lmdroid.ui.settings.ApiProfileListViewModel
import com.suzuri.lmdroid.ui.settings.AssistantSettingsViewModel
import com.suzuri.lmdroid.ui.settings.BraveSearchProfileEditViewModel
import com.suzuri.lmdroid.ui.settings.LocationSettingsViewModel
import com.suzuri.lmdroid.ui.settings.SettingsExportViewModel
import com.suzuri.lmdroid.ui.settings.SettingsImportViewModel
import com.suzuri.lmdroid.ui.settings.SettingsViewModel
import com.suzuri.lmdroid.ui.settings.SystemPromptEditViewModel
import com.suzuri.lmdroid.ui.settings.SystemPromptListViewModel
import com.suzuri.lmdroid.ui.settings.SystemSettingsViewModel
import com.suzuri.lmdroid.ui.settings.VoiceSettingsViewModel
import com.suzuri.lmdroid.ui.settings.VoicevoxProfileEditViewModel
import com.suzuri.lmdroid.ui.settings.WebSearchSettingsViewModel

class ViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ChatViewModel::class.java) ->
            ChatViewModel(
                container.conversationRepository,
                container.settingsRepository,
                container.apiProfileRepository,
                container.attachmentFileStore,
                container.audioRecorder,
                container.systemPromptRepository,
                container.json,
            ) as T

        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(container.apiProfileRepository, container.openAiApiClient) as T

        modelClass.isAssignableFrom(ApiProfileListViewModel::class.java) ->
            ApiProfileListViewModel(container.apiProfileRepository) as T

        modelClass.isAssignableFrom(SystemSettingsViewModel::class.java) ->
            SystemSettingsViewModel(container.apiProfileRepository, container.settingsRepository) as T

        modelClass.isAssignableFrom(WebSearchSettingsViewModel::class.java) ->
            WebSearchSettingsViewModel(container.settingsRepository, container.apiProfileRepository) as T

        modelClass.isAssignableFrom(BraveSearchProfileEditViewModel::class.java) ->
            BraveSearchProfileEditViewModel(container.apiProfileRepository, container.braveSearchClient) as T

        modelClass.isAssignableFrom(VoicevoxProfileEditViewModel::class.java) ->
            VoicevoxProfileEditViewModel(container.apiProfileRepository, container.voicevoxCompatibleClient) as T

        modelClass.isAssignableFrom(VoiceSettingsViewModel::class.java) ->
            VoiceSettingsViewModel(container.settingsRepository, container.apiProfileRepository) as T

        modelClass.isAssignableFrom(HistoryViewModel::class.java) ->
            HistoryViewModel(container.conversationRepository) as T

        modelClass.isAssignableFrom(SettingsExportViewModel::class.java) ->
            SettingsExportViewModel(container.settingsExporter) as T

        modelClass.isAssignableFrom(SettingsImportViewModel::class.java) ->
            SettingsImportViewModel(container.settingsImporter) as T

        modelClass.isAssignableFrom(LocationSettingsViewModel::class.java) ->
            LocationSettingsViewModel(container.settingsRepository) as T

        modelClass.isAssignableFrom(SystemPromptListViewModel::class.java) ->
            SystemPromptListViewModel(container.systemPromptRepository) as T

        modelClass.isAssignableFrom(SystemPromptEditViewModel::class.java) ->
            SystemPromptEditViewModel(container.systemPromptRepository) as T

        modelClass.isAssignableFrom(AssistViewModel::class.java) ->
            AssistViewModel(container.conversationRepository, container.settingsRepository, container.assistSpeechPlayer) as T

        modelClass.isAssignableFrom(AssistantSettingsViewModel::class.java) ->
            AssistantSettingsViewModel(container.apiProfileRepository, container.settingsRepository) as T

        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
