package com.suzuri.lmdroid.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.db.ApiProfileEntity

@Composable
fun ImageGenerationProfileEditScreen(
    viewModel: ImageGenerationProfileEditViewModel,
    profileId: Long,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(profileId) {
        viewModel.loadProfile(profileId)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.onModelFileSelected(uri.toString())
        }
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

        val showApiKey = uiState.providerType != ApiProfileEntity.PROVIDER_LOCAL
        if (showApiKey) {
            OutlinedTextField(
                value = uiState.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                label = { Text(stringResource(R.string.settings_api_key_label)) },
                placeholder = { Text("APIキーを入力") },
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
        }

        val showBaseUrl = uiState.providerType == ApiProfileEntity.PROVIDER_STABLE_DIFFUSION ||
                uiState.providerType == ApiProfileEntity.PROVIDER_COMFYUI ||
                uiState.providerType == ApiProfileEntity.PROVIDER_LOCAL
        
        if (showBaseUrl) {
            val isLocal = uiState.providerType == ApiProfileEntity.PROVIDER_LOCAL
            
            if (isLocal) {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = stringResource(R.string.settings_local_model_mode_label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Column(Modifier.selectableGroup()) {
                    LocalModelMode.entries.forEach { mode ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .selectable(
                                    selected = (uiState.localModelMode == mode),
                                    onClick = { viewModel.onLocalModelModeChange(mode) },
                                    role = Role.RadioButton
                                )
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (uiState.localModelMode == mode),
                                onClick = null // selected row handles this
                            )
                            Text(
                                text = when (mode) {
                                    LocalModelMode.FILE -> stringResource(R.string.settings_local_model_mode_file)
                                    LocalModelMode.URL -> stringResource(R.string.settings_local_model_mode_url)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (uiState.localModelMode == LocalModelMode.FILE) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.settings_model_file_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { filePickerLauncher.launch("*/*") }) {
                                Text(stringResource(R.string.settings_local_model_browse))
                            }
                            Spacer(modifier = Modifier.size(16.dp))
                            Text(
                                text = uiState.baseUrl.substringAfterLast("/").ifBlank { "未選択" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_local_model_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = uiState.baseUrl,
                        onValueChange = viewModel::onBaseUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_model_file_label)) },
                        placeholder = { Text(stringResource(R.string.settings_model_file_placeholder)) },
                        supportingText = { Text(stringResource(R.string.settings_local_model_description)) },
                        singleLine = true,
                    )
                }
            } else {
                OutlinedTextField(
                    value = uiState.baseUrl,
                    onValueChange = viewModel::onBaseUrlChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    label = { Text(stringResource(R.string.settings_base_url_label)) },
                    placeholder = { Text("http://10.0.2.2:7860") },
                    singleLine = true,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = uiState.imageWidth,
                onValueChange = viewModel::onImageWidthChange,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.settings_image_width_label)) },
                placeholder = { Text("512") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = uiState.imageHeight,
                onValueChange = viewModel::onImageHeightChange,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.settings_image_height_label)) },
                placeholder = { Text("512") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

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
