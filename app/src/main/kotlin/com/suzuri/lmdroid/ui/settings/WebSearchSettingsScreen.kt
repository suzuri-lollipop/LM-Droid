package com.suzuri.lmdroid.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R

/** Settings → Web検索: on/off toggle + Brave Search API key for the web_search/fetch_webpage tools. */
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

        OutlinedTextField(
            value = uiState.apiKey,
            onValueChange = viewModel::onApiKeyChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            label = { Text(stringResource(R.string.websearch_api_key_label)) },
            placeholder = { Text(stringResource(R.string.websearch_api_key_placeholder)) },
            singleLine = true,
            visualTransformation = if (uiState.isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = viewModel::onToggleKeyVisibility) {
                    Icon(
                        imageVector = if (uiState.isKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                    )
                }
            },
        )

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
            OutlinedButton(
                onClick = viewModel::onTestConnection,
                modifier = Modifier.padding(start = 12.dp),
            ) {
                Text(stringResource(R.string.settings_test_connection))
            }
        }

        if (uiState.saved) {
            Text(
                text = stringResource(R.string.settings_saved),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        when (val testState = uiState.testState) {
            is TestConnectionState.Idle -> Unit
            is TestConnectionState.Testing -> {
                Row(modifier = Modifier.padding(top = 12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp))
                }
            }
            is TestConnectionState.Success -> {
                Text(
                    text = stringResource(R.string.settings_test_success),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            is TestConnectionState.Failure -> {
                Text(
                    text = testState.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
