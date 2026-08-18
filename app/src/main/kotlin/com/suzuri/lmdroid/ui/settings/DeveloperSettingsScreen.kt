package com.suzuri.lmdroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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

/**
 * Settings → 開発者向け (developer options): developer-only controls for the local voice-input (STT)
 * engine. Two things live here — the Bluetooth routing strategy (explicit communication-mode routing
 * vs. the pre-BT-support plain capture) and the "dump captured audio" diagnostic with its file list
 * and delete-all. See [DeveloperSettingsViewModel].
 */
@Composable
fun DeveloperSettingsScreen(viewModel: DeveloperSettingsViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.developer_settings_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Text(stringResource(R.string.developer_bt_routing_label), style = MaterialTheme.typography.titleSmall)
        Text(
            text = stringResource(R.string.developer_bt_routing_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.onBluetoothRoutingModeChanged("auto") },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = uiState.bluetoothRoutingMode == "auto",
                onClick = { viewModel.onBluetoothRoutingModeChanged("auto") },
            )
            Text(stringResource(R.string.developer_bt_routing_auto))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.onBluetoothRoutingModeChanged("disabled") },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = uiState.bluetoothRoutingMode == "disabled",
                onClick = { viewModel.onBluetoothRoutingModeChanged("disabled") },
            )
            Text(stringResource(R.string.developer_bt_routing_disabled))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.developer_stt_capture_label), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(R.string.developer_stt_capture_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Switch(
                checked = uiState.sttCaptureDebug,
                onCheckedChange = viewModel::onSttCaptureDebugChanged,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.developer_stt_captured_files),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::onRefreshCaptures) {
                Text(stringResource(R.string.developer_stt_captured_refresh))
            }
        }

        if (uiState.captureFiles.isEmpty()) {
            Text(
                text = stringResource(R.string.developer_stt_captured_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            uiState.captureFiles.forEach { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatSize(file.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = viewModel::onDeleteAllCaptures,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.developer_stt_captured_delete_all))
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}
