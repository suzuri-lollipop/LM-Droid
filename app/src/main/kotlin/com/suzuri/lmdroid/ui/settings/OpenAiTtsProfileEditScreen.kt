package com.suzuri.lmdroid.ui.settings

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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.settings.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenAiTtsProfileEditScreen(
    viewModel: OpenAiTtsProfileEditViewModel,
    profileId: Long,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    var isKeyVisible by rememberSaveable { mutableStateOf(false) }

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
            visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                    Icon(
                        imageVector = if (isKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
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

        var modelExpanded by rememberSaveable { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = modelExpanded,
            onExpandedChange = { modelExpanded = it },
            modifier = Modifier.padding(top = 16.dp),
        ) {
            OutlinedTextField(
                value = uiState.model,
                onValueChange = viewModel::onModelChange,
                readOnly = false,
                label = { Text(stringResource(R.string.openai_tts_model_label)) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            )
            val filteredModels = uiState.availableModels.filter { it.contains(uiState.model, ignoreCase = true) }
            if (filteredModels.isNotEmpty()) {
                DropdownMenu(
                    expanded = modelExpanded,
                    onDismissRequest = { modelExpanded = false },
                    modifier = Modifier.exposedDropdownSize(),
                ) {
                    filteredModels.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                viewModel.onModelChange(option)
                                modelExpanded = false
                            },
                        )
                    }
                }
            }
        }

        var voiceExpanded by rememberSaveable { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = voiceExpanded,
            onExpandedChange = { voiceExpanded = it },
            modifier = Modifier.padding(top = 16.dp),
        ) {
            OutlinedTextField(
                value = uiState.voice,
                onValueChange = viewModel::onVoiceChange,
                readOnly = false,
                label = { Text(stringResource(R.string.openai_tts_voice_label)) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            )
            val filteredVoices = uiState.availableVoices.filter { it.contains(uiState.voice, ignoreCase = true) }
            if (filteredVoices.isNotEmpty()) {
                DropdownMenu(
                    expanded = voiceExpanded,
                    onDismissRequest = { voiceExpanded = false },
                    modifier = Modifier.exposedDropdownSize(),
                ) {
                    filteredVoices.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                viewModel.onVoiceChange(option)
                                voiceExpanded = false
                            },
                        )
                    }
                }
            }
        }

        Row(modifier = Modifier.padding(top = 20.dp)) {
            Button(onClick = viewModel::onSave) {
                Text(stringResource(R.string.settings_save))
            }
            OutlinedButton(
                onClick = viewModel::onFetchOptions,
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
