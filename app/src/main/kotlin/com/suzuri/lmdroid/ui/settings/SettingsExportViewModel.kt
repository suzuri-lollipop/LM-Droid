package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import com.suzuri.lmdroid.data.settings.SettingsExporter

/** Thin wrapper so the export button (in MainActivity, which owns the file-picker launcher and ContentResolver access needed to write the result) goes through a ViewModel like everything else, rather than reaching into AppContainer directly. */
class SettingsExportViewModel(private val settingsExporter: SettingsExporter) : ViewModel() {
    suspend fun exportToYaml(): String = settingsExporter.exportToYaml()
}
