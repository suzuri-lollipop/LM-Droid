package com.suzuri.lmdroid.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.character.CharacterModelType

/**
 * Settings → キャラクター: configures the character shown on the assistant overlay's
 * novel-game-style stage (see AssistScreen's AssistStage). Live2D and MMD are listed but
 * disabled until their renderers land (later phases of the character plan).
 */
@Composable
fun CharacterSettingsScreen(viewModel: CharacterSettingsViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val spritePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.onSpriteSelected(uri)
    }
    val backgroundPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.onBackgroundSelected(uri)
    }

    val importFailedMessage = stringResource(R.string.character_import_failed)
    LaunchedEffect(uiState.importFailed) {
        if (uiState.importFailed) {
            Toast.makeText(context, importFailedMessage, Toast.LENGTH_SHORT).show()
            viewModel.onImportFailedShown()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.character_settings_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))

        Text(
            text = stringResource(R.string.character_model_type_label),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))

        CharacterModelTypeRadioRow(
            label = stringResource(R.string.character_model_type_none),
            selected = uiState.settings.modelType == CharacterModelType.NONE,
            enabled = true,
            onSelect = { viewModel.onSelectModelType(CharacterModelType.NONE) },
        )
        HorizontalDivider()
        CharacterModelTypeRadioRow(
            label = stringResource(R.string.character_model_type_static),
            selected = uiState.settings.modelType == CharacterModelType.STATIC,
            enabled = true,
            onSelect = { viewModel.onSelectModelType(CharacterModelType.STATIC) },
        )
        HorizontalDivider()
        CharacterModelTypeRadioRow(
            label = stringResource(R.string.character_model_type_live2d),
            selected = uiState.settings.modelType == CharacterModelType.LIVE2D,
            enabled = false,
            onSelect = { viewModel.onSelectModelType(CharacterModelType.LIVE2D) },
        )
        HorizontalDivider()
        CharacterModelTypeRadioRow(
            label = stringResource(R.string.character_model_type_mmd),
            selected = uiState.settings.modelType == CharacterModelType.MMD,
            enabled = false,
            onSelect = { viewModel.onSelectModelType(CharacterModelType.MMD) },
        )

        HorizontalDivider(modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))

        ListItem(
            headlineContent = { Text(stringResource(R.string.character_sprite_label)) },
            supportingContent = {
                Row {
                    Button(
                        onClick = { spritePickerLauncher.launch("image/*") },
                        enabled = !uiState.importing,
                    ) {
                        Text(stringResource(R.string.character_sprite_select))
                    }
                    if (uiState.settings.modelPath != null) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = viewModel::onClearSprite) {
                            Text(stringResource(R.string.character_sprite_clear))
                        }
                    }
                }
            },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.character_background_label)) },
            supportingContent = {
                Row {
                    Button(
                        onClick = { backgroundPickerLauncher.launch("image/*") },
                        enabled = !uiState.importing,
                    ) {
                        Text(stringResource(R.string.character_sprite_select))
                    }
                    if (uiState.settings.backgroundPath != null) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = viewModel::onClearBackground) {
                            Text(stringResource(R.string.character_sprite_clear))
                        }
                    }
                }
            },
        )

        HorizontalDivider(modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))

        // Kept as a local draft and committed on drag-end so DataStore isn't hammered with a
        // write per pixel of slider movement.
        var scaleDraft by remember { mutableFloatStateOf(uiState.settings.scale) }
        LaunchedEffect(uiState.settings.scale) { scaleDraft = uiState.settings.scale }
        ListItem(
            headlineContent = { Text(stringResource(R.string.character_scale_label)) },
            supportingContent = {
                Slider(
                    value = scaleDraft,
                    onValueChange = { scaleDraft = it },
                    onValueChangeFinished = { viewModel.onScaleChanged(scaleDraft) },
                    valueRange = 0.5f..2f,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.character_typewriter_label)) },
            supportingContent = { Text(stringResource(R.string.character_typewriter_description)) },
            trailingContent = {
                Switch(
                    checked = uiState.settings.typewriterEnabled,
                    onCheckedChange = viewModel::onToggleTypewriter,
                )
            },
            modifier = Modifier.clickable { viewModel.onToggleTypewriter(!uiState.settings.typewriterEnabled) },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.character_lip_sync_label)) },
            supportingContent = { Text(stringResource(R.string.character_lip_sync_description)) },
            trailingContent = {
                Switch(
                    checked = uiState.settings.lipSyncEnabled,
                    onCheckedChange = viewModel::onToggleLipSync,
                )
            },
            modifier = Modifier.clickable { viewModel.onToggleLipSync(!uiState.settings.lipSyncEnabled) },
        )
        HorizontalDivider()
    }
}

@Composable
private fun CharacterModelTypeRadioRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        },
        modifier = if (enabled) Modifier.clickable(onClick = onSelect) else Modifier,
    )
}
