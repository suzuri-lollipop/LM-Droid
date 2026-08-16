package com.suzuri.lmdroid.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.character.CharacterModelType
import com.suzuri.lmdroid.data.character.CharacterSettings
import com.suzuri.lmdroid.ui.character.CharacterUiState
import com.suzuri.lmdroid.ui.character.MmdNative
import com.suzuri.lmdroid.ui.character.MmdSurface
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

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
    // Primary flow: pick the .pmx file itself (textures are recovered from its folder when the
    // provider allows). The tree picker stays as a fallback for providers where siblings can't
    // be reached from a single-file grant.
    val pmxFilePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.onPmxFileSelected(uri)
    }
    val mmdFolderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onMmdModelSelected(uri)
    }
    val motionPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.onMotionSelected(uri)
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
            enabled = true,
            onSelect = { viewModel.onSelectModelType(CharacterModelType.MMD) },
        )

        HorizontalDivider(modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))

        if (uiState.settings.modelType == CharacterModelType.MMD) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.character_mmd_model_label)) },
                supportingContent = {
                    Column {
                        Text(stringResource(R.string.character_mmd_model_description))
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(
                                onClick = { pmxFilePickerLauncher.launch("*/*") },
                                enabled = !uiState.importing,
                            ) {
                                Text(stringResource(R.string.character_mmd_model_select))
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { mmdFolderPickerLauncher.launch(null) },
                                enabled = !uiState.importing,
                            ) {
                                Text(stringResource(R.string.character_mmd_model_select_folder))
                            }
                        }
                        if (uiState.settings.modelPath != null) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                TextButton(onClick = viewModel::onClearMmd) {
                                    Text(stringResource(R.string.character_sprite_clear))
                                }
                            }
                        }
                    }
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.character_mmd_motion_label)) },
                supportingContent = {
                    Column {
                        Text(stringResource(R.string.character_mmd_motion_description))
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { motionPickerLauncher.launch("*/*") },
                            enabled = !uiState.importing,
                        ) {
                            Text(stringResource(R.string.character_sprite_select))
                        }
                    }
                },
            )
            HorizontalDivider()
            if (uiState.settings.modelPath != null && MmdNative.isAvailable) {
                MmdFramingEditor(settings = uiState.settings, viewModel = viewModel)
                HorizontalDivider()
            }
        }

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
        if (uiState.settings.lipSyncEnabled) {
            HorizontalDivider()
            var thresholdDraft by remember { mutableFloatStateOf(uiState.settings.lipSyncThreshold) }
            LaunchedEffect(uiState.settings.lipSyncThreshold) { thresholdDraft = uiState.settings.lipSyncThreshold }
            ListItem(
                headlineContent = { Text(stringResource(R.string.character_lip_sync_threshold_label)) },
                supportingContent = {
                    Column {
                        Text(stringResource(R.string.character_lip_sync_threshold_description))
                        Slider(
                            value = thresholdDraft,
                            onValueChange = { thresholdDraft = it },
                            onValueChangeFinished = { viewModel.onLipSyncThresholdChanged(thresholdDraft) },
                            valueRange = 5f..80f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
        }
        HorizontalDivider()
    }
}

/**
 * The MMD "display range" GUI: a live preview of the model, pinch-to-zoom and drag-to-pan
 * directly on it. Mirrors what [CharacterStage] renders on the assistant overlay, so what the
 * user frames here is exactly what shows up there (see MmdSurface's zoom/panX/panY and
 * MmdRenderer::setFraming on the native side).
 */
@Composable
private fun MmdFramingEditor(settings: CharacterSettings, viewModel: CharacterSettingsViewModel) {
    var zoomDraft by remember { mutableFloatStateOf(settings.mmdZoom) }
    var panXDraft by remember { mutableFloatStateOf(settings.mmdPanX) }
    var panYDraft by remember { mutableFloatStateOf(settings.mmdPanY) }
    LaunchedEffect(settings.mmdZoom, settings.mmdPanX, settings.mmdPanY) {
        zoomDraft = settings.mmdZoom
        panXDraft = settings.mmdPanX
        panYDraft = settings.mmdPanY
    }
    // Commits to DataStore once the gesture pauses rather than on every touch-move frame (same
    // reasoning as the scale slider's onValueChangeFinished above) — detectTransformGestures has
    // no drag-end callback of its own, so a debounce stands in for it.
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(zoomDraft, panXDraft, panYDraft) }
            .drop(1)
            .debounce(300)
            .collect { (zoom, panX, panY) -> viewModel.onMmdFramingChanged(zoom, panX, panY) }
    }

    ListItem(
        headlineContent = { Text(stringResource(R.string.character_mmd_framing_label)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.character_mmd_framing_description))
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                zoomDraft = (zoomDraft * gestureZoom).coerceIn(0.3f, 4f)
                                // World X/right and touch X/right share sign, but world Y is
                                // up-positive while the touch delta is down-positive — see the
                                // derivation in the MMD GUI change notes; this is what makes the
                                // model track the finger in both axes rather than move opposite
                                // it on one of them.
                                panXDraft = (panXDraft - pan.x / size.width).coerceIn(-1f, 1f)
                                panYDraft = (panYDraft + pan.y / size.height).coerceIn(-1f, 1f)
                            }
                        },
                ) {
                    MmdSurface(
                        pmxPath = settings.modelPath!!,
                        vmdPath = settings.motionPath,
                        characterState = CharacterUiState.Idle,
                        lipSyncEnabled = false,
                        zoom = zoomDraft,
                        panX = panXDraft,
                        panY = panYDraft,
                        modifier = Modifier.matchParentSize(),
                    )
                }
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = {
                        zoomDraft = 1f
                        panXDraft = 0f
                        panYDraft = 0f
                        viewModel.onResetMmdFraming()
                    }) {
                        Text(stringResource(R.string.character_mmd_framing_reset))
                    }
                }
            }
        },
    )
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
