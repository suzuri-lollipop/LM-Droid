package com.suzuri.lmdroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
 * Settings → 音楽: on/off toggle for the "play_music" tool, plus which installed music app
 * (Spotify, YouTube Musicなど) it should target directly — leaving none selected lets the system
 * resolve it itself each time the tool is called.
 */
@Composable
fun MusicSettingsScreen(viewModel: MusicSettingsViewModel, modifier: Modifier = Modifier) {
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
                Text(stringResource(R.string.music_enable_label), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.music_enable_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Switch(checked = uiState.enabled, onCheckedChange = viewModel::onEnabledChange)
        }

        HorizontalDivider(modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))

        Text(stringResource(R.string.music_app_label), style = MaterialTheme.typography.titleSmall)
        Text(
            text = stringResource(R.string.music_app_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        if (uiState.apps.isEmpty()) {
            Text(
                text = stringResource(R.string.music_app_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            uiState.apps.forEach { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.onSelectApp(app.packageName) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = app.packageName == uiState.selectedPackageName,
                        onClick = { viewModel.onSelectApp(app.packageName) },
                    )
                    Text(app.label)
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))

        Text(stringResource(R.string.music_youtube_api_label), style = MaterialTheme.typography.titleSmall)
        Text(
            text = stringResource(R.string.music_youtube_api_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        if (uiState.youtubeApiProfiles.isEmpty()) {
            Text(
                text = stringResource(R.string.music_youtube_api_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            uiState.youtubeApiProfiles.forEach { profile ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.onSelectYoutubeApiProfile(profile.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = profile.id == uiState.selectedYoutubeApiProfileId,
                        onClick = { viewModel.onSelectYoutubeApiProfile(profile.id) },
                    )
                    Text(profile.name)
                }
            }
        }
    }
}
