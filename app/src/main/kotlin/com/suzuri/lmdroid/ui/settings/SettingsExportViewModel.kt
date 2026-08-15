package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import com.suzuri.lmdroid.data.settings.SettingsExporter

/** Thin wrapper so the export button (in MainActivity, which owns the file-picker launcher and ContentResolver access needed to write the result) goes through a ViewModel like everything else, rather than reaching into AppContainer directly. */
class SettingsExportViewModel(private val settingsExporter: SettingsExporter) : ViewModel() {
    /** Empty [password] → the plain local-backup YAML; non-empty → the password-protected export that carries usable API keys. */
    suspend fun exportToYaml(password: String): String =
        if (password.isEmpty()) settingsExporter.exportToYaml()
        else settingsExporter.exportToEncryptedYaml(password)
}
