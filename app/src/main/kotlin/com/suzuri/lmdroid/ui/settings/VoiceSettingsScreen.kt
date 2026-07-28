package com.suzuri.lmdroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R

/**
 * Settings → 音声: which backend speaks the assistant overlay's replies aloud — this device's own
 * built-in text-to-speech (the default), or one of the registered VOICEVOX-compatible profiles
 * (API設定). The profile's own URL/speaker id are edited on its own screen, not here — this only
 * picks which backend (if any) is active.
 */
@Composable
fun VoiceSettingsScreen(viewModel: VoiceSettingsViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.voice_profile_label), style = MaterialTheme.typography.titleSmall)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.onSelectOnDevice() }
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = uiState.selectedProfileId == null,
                onClick = viewModel::onSelectOnDevice,
            )
            Text(stringResource(R.string.voice_on_device_option))
        }

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

        if (uiState.profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.voice_profile_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
