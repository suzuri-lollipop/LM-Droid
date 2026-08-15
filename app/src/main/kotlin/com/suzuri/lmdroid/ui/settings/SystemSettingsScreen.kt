package com.suzuri.lmdroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.ui.settings.components.SettingsMenuRow

/**
 * Settings → システム: choose which (profile, model) generates conversation titles and the
 * empty-state prompt suggestions, independently of the model used for chat itself, plus the
 * settings backup actions (export/import a YAML snapshot of every setting).
 */
@Composable
fun SystemSettingsScreen(
    viewModel: SystemSettingsViewModel,
    onExportSettings: () -> Unit,
    onImportSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.settings_system_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        HorizontalDivider()
        // weight(1f): without it the LazyColumn measures against the Column's FULL height
        // (not the space left after the description header) and its bottom rows — notably
        // エクスポート/インポート — overflow past the screen edge in landscape.
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_system_use_chat_model)) },
                    leadingContent = {
                        RadioButton(selected = uiState.selectedModel == null, onClick = viewModel::onUseChatModel)
                    },
                    modifier = Modifier.clickable { viewModel.onUseChatModel() },
                )
                HorizontalDivider()
            }
            items(uiState.availableModels, key = { "${it.profileId}:${it.modelId}" }) { option ->
                val isSelected = uiState.selectedModel?.profileId == option.profileId &&
                    uiState.selectedModel?.model == option.modelId
                ListItem(
                    headlineContent = { Text(option.modelId) },
                    supportingContent = { Text(option.profileName) },
                    leadingContent = {
                        RadioButton(selected = isSelected, onClick = { viewModel.onSelectModel(option) })
                    },
                    modifier = Modifier.clickable { viewModel.onSelectModel(option) },
                )
                HorizontalDivider()
            }
            item {
                SettingsMenuRow(
                    title = stringResource(R.string.settings_export_title),
                    onClick = onExportSettings,
                )
                HorizontalDivider()
                SettingsMenuRow(
                    title = stringResource(R.string.settings_import_title),
                    onClick = onImportSettings,
                )
            }
        }
    }
}
