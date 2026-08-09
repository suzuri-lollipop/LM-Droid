package com.suzuri.lmdroid.ui.settings

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.settings.AssistantToolLaunchTiming
import com.suzuri.lmdroid.service.WakeWordStatus
/**
 * Settings → アシスタント: registers this app as the device's assist app, so the system assist
 * gesture opens AssistActivity. On Android 10+ this goes through RoleManager's system picker;
 * older versions fall back to the legacy "Assist & voice input" settings screen, best-effort,
 * since not every OEM skin still exposes it. Which physical gesture (power-button long-press,
 * home long-press, a swipe, ...) actually triggers assist is a device/OEM setting this app has no
 * control over — registering here only makes the app *eligible* to be chosen. Also picks which
 * (profile, model) the assistant overlay itself replies with (see [AssistantSettingsViewModel]),
 * independently of whatever's active for chat.
 */
@Composable
fun AssistantSettingsScreen(viewModel: AssistantSettingsViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    fun isRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    var roleHeld by remember { mutableStateOf(isRoleHeld()) }

    val roleRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        roleHeld = isRoleHeld()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.assistant_settings_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(
                if (roleHeld) R.string.assistant_settings_status_held else R.string.assistant_settings_status_not_held,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = context.getSystemService(RoleManager::class.java)
                    if ((roleManager != null) && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                        roleRequestLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                    }
                } else {
                    // Raw action string (rather than a possibly-version-gated Settings constant):
                    // best-effort only, since several OEM skins have long since removed this
                    // screen — silently doing nothing on ActivityNotFoundException is acceptable
                    // here, there's no more specific fallback screen to send the user to instead.
                    runCatching {
                        context.startActivity(Intent("android.settings.VOICE_INPUT_SETTINGS"))
                    }.onFailure { if (it !is ActivityNotFoundException) throw it }
                }
            },
        ) {
            Text(stringResource(R.string.assistant_settings_set_button))
        }

        HorizontalDivider(modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))

        Text(
            text = stringResource(R.string.assistant_settings_model_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_system_use_chat_model)) },
            leadingContent = {
                RadioButton(selected = uiState.selectedModel == null, onClick = viewModel::onUseChatModel)
            },
            modifier = Modifier.clickable { viewModel.onUseChatModel() },
        )
        HorizontalDivider()
        uiState.availableModels.forEach { option ->
            val isSelected = uiState.selectedModel?.profileId == option.profileId &&
                uiState.selectedModel?.model == option.modelId
            ListItem(
                headlineContent = { Text(option.modelId) },
                supportingContent = { Text(option.profileName) },
                leadingContent = {
                    RadioButton(selected = isSelected, onClick = { viewModel.onSelectModel(option) })
                },
                modifier = Modifier.clickable { viewModel.onSelectModel(option) },
            )
            HorizontalDivider()
        }

        HorizontalDivider(modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))

        Text(
            text = stringResource(R.string.settings_wake_word_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_wake_word_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_wake_word_enable)) },
            trailingContent = {
                Switch(checked = uiState.wakeWordEnabled, onCheckedChange = viewModel::onToggleWakeWord)
            },
            modifier = Modifier.clickable { viewModel.onToggleWakeWord(!uiState.wakeWordEnabled) },
        )

        if (uiState.wakeWordEnabled) {
            var wakeWordDraft by remember { mutableStateOf(uiState.wakeWord) }
            LaunchedEffect(uiState.wakeWord) {
                if (wakeWordDraft != uiState.wakeWord) {
                    wakeWordDraft = uiState.wakeWord
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = wakeWordDraft,
                onValueChange = {
                    wakeWordDraft = it
                    viewModel.onUpdateWakeWord(it)
                },
                label = { Text(stringResource(R.string.settings_wake_word_label)) },
                placeholder = { Text(stringResource(R.string.settings_wake_word_placeholder)) },
                supportingText = { Text(stringResource(R.string.settings_wake_word_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Debug Info",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Status: ${
                            when (uiState.wakeWordStatus) {
                                is WakeWordStatus.Idle -> "Idle"
                                is WakeWordStatus.LoadingModel -> "Loading Model..."
                                is WakeWordStatus.Listening -> "Listening"
                                is WakeWordStatus.Error -> "Error: ${(uiState.wakeWordStatus as WakeWordStatus.Error).message}"
                            }
                        }",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (uiState.wakeWordLastResult.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Last Result: ${uiState.wakeWordLastResult}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))

        Text(
            text = stringResource(R.string.assistant_settings_tool_timing_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        ListItem(
            headlineContent = { Text(stringResource(R.string.assistant_settings_tool_timing_while_speaking)) },
            leadingContent = {
                RadioButton(
                    selected = uiState.toolLaunchTiming == AssistantToolLaunchTiming.WHILE_SPEAKING,
                    onClick = { viewModel.onSelectToolLaunchTiming(AssistantToolLaunchTiming.WHILE_SPEAKING) },
                )
            },
            modifier = Modifier.clickable { viewModel.onSelectToolLaunchTiming(AssistantToolLaunchTiming.WHILE_SPEAKING) },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.assistant_settings_tool_timing_after_speaking)) },
            leadingContent = {
                RadioButton(
                    selected = uiState.toolLaunchTiming == AssistantToolLaunchTiming.AFTER_SPEAKING,
                    onClick = { viewModel.onSelectToolLaunchTiming(AssistantToolLaunchTiming.AFTER_SPEAKING) },
                )
            },
            modifier = Modifier.clickable { viewModel.onSelectToolLaunchTiming(AssistantToolLaunchTiming.AFTER_SPEAKING) },
        )
        HorizontalDivider()
    }
}
