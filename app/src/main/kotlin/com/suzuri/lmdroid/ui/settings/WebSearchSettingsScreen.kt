package com.suzuri.lmdroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R

/**
 * Settings → Web検索: on/off toggle + which registered Brave Search profile (API設定) supplies the
 * key, for the web_search/fetch_webpage tools. The key itself is edited on that profile's own
 * screen, not here — this only picks which one (if any) is active.
 */
@Composable
fun WebSearchSettingsScreen(viewModel: WebSearchSettingsViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.websearch_enable_label), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.websearch_enable_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Switch(checked = uiState.enabled, onCheckedChange = viewModel::onEnabledChange)
        }

        HorizontalDivider(modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))

        Text(stringResource(R.string.websearch_profile_label), style = MaterialTheme.typography.titleSmall)
        if (uiState.profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.websearch_profile_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            uiState.profiles.forEach { profile ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.onSelectProfile(profile.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = profile.id == uiState.selectedProfileId,
                        onClick = { viewModel.onSelectProfile(profile.id) },
                    )
                    Text(profile.name)
                }
            }
        }

        OutlinedTextField(
            value = uiState.maxToolRounds,
            onValueChange = viewModel::onMaxToolRoundsChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            label = { Text(stringResource(R.string.websearch_max_rounds_label)) },
            placeholder = { Text(stringResource(R.string.websearch_max_rounds_placeholder)) },
            supportingText = { Text(stringResource(R.string.websearch_max_rounds_description)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        Row(modifier = Modifier.padding(top = 20.dp)) {
            Button(onClick = viewModel::onSave) {
                Text(stringResource(R.string.settings_save))
            }
        }

        if (uiState.saved) {
            Text(
                text = stringResource(R.string.settings_saved),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
