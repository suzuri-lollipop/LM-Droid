package com.suzuri.lmdroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.suzuri.lmdroid.AppContainer
import com.suzuri.lmdroid.ui.chat.ChatViewModel
import com.suzuri.lmdroid.ui.history.HistoryViewModel
import com.suzuri.lmdroid.ui.settings.ApiProfileListViewModel
import com.suzuri.lmdroid.ui.settings.LocationSettingsViewModel
import com.suzuri.lmdroid.ui.settings.SettingsExportViewModel
import com.suzuri.lmdroid.ui.settings.SettingsViewModel
import com.suzuri.lmdroid.ui.settings.SystemSettingsViewModel
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
                container.json,
            ) as T

        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(container.apiProfileRepository, container.openAiApiClient) as T

        modelClass.isAssignableFrom(ApiProfileListViewModel::class.java) ->
            ApiProfileListViewModel(container.apiProfileRepository) as T

        modelClass.isAssignableFrom(SystemSettingsViewModel::class.java) ->
            SystemSettingsViewModel(container.apiProfileRepository, container.settingsRepository) as T

        modelClass.isAssignableFrom(WebSearchSettingsViewModel::class.java) ->
            WebSearchSettingsViewModel(container.settingsRepository, container.braveSearchClient) as T

        modelClass.isAssignableFrom(HistoryViewModel::class.java) ->
            HistoryViewModel(container.conversationRepository) as T

        modelClass.isAssignableFrom(SettingsExportViewModel::class.java) ->
            SettingsExportViewModel(container.settingsExporter) as T

        modelClass.isAssignableFrom(LocationSettingsViewModel::class.java) ->
            LocationSettingsViewModel(container.settingsRepository) as T

        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
