package com.suzuri.lmdroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.stt.SpeechModel

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

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        Text(stringResource(R.string.settings_stt_category_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        uiState.sttModels.forEach { modelUi ->
            val model = modelUi.model
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = modelUi.isAvailable) { viewModel.onSelectSttModel(model.id) }
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        RadioButton(
                            selected = model.id == uiState.selectedSttModelId,
                            onClick = { if (modelUi.isAvailable) viewModel.onSelectSttModel(model.id) },
                            enabled = modelUi.isAvailable
                        )
                        Column {
                            Text(
                                text = model.name,
                                color = if (modelUi.isAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                            Text(
                                text = formatSize(model.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (model.isBundled) {
                        Text(
                            text = stringResource(R.string.stt_model_bundled),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    } else if (!modelUi.isAvailable && !modelUi.isDownloading) {
                        Button(onClick = { viewModel.onDownloadModel(model) }) {
                            Text(stringResource(R.string.stt_model_download))
                        }
                    } else if (modelUi.isDownloading) {
                        Text(
                            text = stringResource(R.string.stt_model_downloading, (modelUi.progress * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        TextButton(onClick = { viewModel.onDeleteModel(model) }) {
                            Text(stringResource(R.string.stt_model_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (modelUi.isDownloading) {
                    LinearProgressIndicator(
                        progress = { modelUi.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp, end = 16.dp, top = 4.dp)
                    )
                }
            }
        }

        uiState.downloadError?.let { error ->
            Text(
                text = "${stringResource(R.string.stt_model_download_failed)}: $error",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024 * 1024)
    return if (mb >= 1024) {
        String.format("%.1f GB", mb / 1024f)
    } else {
        "$mb MB"
    }
}
