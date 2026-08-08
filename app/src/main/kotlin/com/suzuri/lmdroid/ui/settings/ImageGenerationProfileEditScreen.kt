package com.suzuri.lmdroid.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
            OutlinedTextField(
                value = uiState.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                label = {
                    Text(
                        if (isLocal) stringResource(R.string.settings_model_file_label)
                        else stringResource(R.string.settings_base_url_label)
                    )
                },
                placeholder = {
                    Text(
                        if (isLocal) stringResource(R.string.settings_model_file_placeholder)
                        else "http://10.0.2.2:7860"
                    )
                },
                supportingText = if (isLocal) {
                    { Text(stringResource(R.string.settings_local_model_description)) }
                } else null,
                singleLine = true,
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
