package com.suzuri.lmdroid.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.tts.VoicevoxCompatibleClient

/** API設定 → profile list → this profile's edit form (name/URL/話者ID) for a VOICEVOX-compatible profile — see [BraveSearchProfileEditScreen] for the API-key-based equivalent. */
@Composable
fun VoicevoxProfileEditScreen(
    viewModel: VoicevoxProfileEditViewModel,
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
            value = uiState.baseUrl,
            onValueChange = viewModel::onBaseUrlChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            label = { Text(stringResource(R.string.settings_base_url_label)) },
            placeholder = { Text(VoicevoxCompatibleClient.DEFAULT_BASE_URL) },
            singleLine = true,
        )

        OutlinedTextField(
            value = uiState.speakerId,
            onValueChange = viewModel::onSpeakerIdChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            label = { Text(stringResource(R.string.voicevox_speaker_id_label)) },
            supportingText = { Text(stringResource(R.string.voicevox_speaker_id_description)) },
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
