package com.suzuri.lmdroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.settings.AppSettings

/** API設定 → profile list → this profile's edit form (API key/base URL) for an OpenAI-compatible endpoint. */
@Composable
fun OpenAiCompatibleScreen(
    viewModel: SettingsViewModel,
    profileId: Long,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(profileId) {
        viewModel.loadProfile(profileId)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = uiState.profileName,
            onValueChange = viewModel::onProfileNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.api_profile_name_label)) },
            singleLine = true,
        )

        OutlinedTextField(
            value = uiState.apiKey,
            onValueChange = viewModel::onApiKeyChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            label = { Text(stringResource(R.string.settings_api_key_label)) },
            placeholder = { Text(stringResource(R.string.settings_api_key_placeholder)) },
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
            value = uiState.baseUrl,
            onValueChange = viewModel::onBaseUrlChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            label = { Text(stringResource(R.string.settings_base_url_label)) },
            placeholder = { Text(AppSettings.DEFAULT_BASE_URL) },
            singleLine = true,
        )

        Text(
            text = stringResource(R.string.api_profile_thinking_default_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp),
        )
        // Seeds the chat screen's 思考 toggle whenever the user switches to one of this profile's
        // models (ChatViewModel.onSelectModel) — e.g. off by default for a Gemma profile run
        // without thinking, on for a Qwen3 profile that reasons by default. サーバー既定 (null)
        // leaves the toggle exactly as the user last set it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = uiState.defaultThinkingEnabled == null,
                onClick = { viewModel.onDefaultThinkingEnabledChange(null) },
                label = { Text(stringResource(R.string.api_profile_thinking_default_unset)) },
            )
            FilterChip(
                selected = uiState.defaultThinkingEnabled == true,
                onClick = { viewModel.onDefaultThinkingEnabledChange(true) },
                label = { Text(stringResource(R.string.api_profile_thinking_default_on)) },
            )
            FilterChip(
                selected = uiState.defaultThinkingEnabled == false,
                onClick = { viewModel.onDefaultThinkingEnabledChange(false) },
                label = { Text(stringResource(R.string.api_profile_thinking_default_off)) },
            )
        }

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

        HorizontalDivider(modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))

        Text(
            text = stringResource(R.string.settings_registered_models_label),
            style = MaterialTheme.typography.titleSmall,
        )
        if (uiState.models.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_registered_models_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            uiState.models.forEach { model ->
                Text(
                    text = model,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
