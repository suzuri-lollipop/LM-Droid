package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import com.suzuri.lmdroid.data.settings.SettingsImporter

/** Thin wrapper so the import button (in MainActivity, which owns the file-picker launcher and ContentResolver access needed to read the picked file) goes through a ViewModel like everything else, rather than reaching into AppContainer directly. */
class SettingsImportViewModel(private val settingsImporter: SettingsImporter) : ViewModel() {
    suspend fun importFromYaml(yamlText: String): Result<Unit> = settingsImporter.importFromYaml(yamlText)
}
