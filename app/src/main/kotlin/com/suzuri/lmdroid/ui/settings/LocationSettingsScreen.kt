package com.suzuri.lmdroid.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.suzuri.lmdroid.R

/**
 * Settings → 位置情報: on/off toggle for the "get_current_location" tool. Turning it on requests
 * the runtime permission right then (rather than waiting until the model first tries to call the
 * tool, mid-reply, with nowhere sensible to prompt from) — if the user denies it, the toggle just
 * doesn't turn on.
 */
@Composable
fun LocationSettingsScreen(viewModel: LocationSettingsViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onEnabledChange(granted) }

    fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.location_enable_label), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.location_enable_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Switch(
                checked = uiState.enabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        if (hasLocationPermission()) {
                            viewModel.onEnabledChange(true)
                        } else {
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    } else {
                        viewModel.onEnabledChange(false)
                    }
                },
            )
        }
    }
}
