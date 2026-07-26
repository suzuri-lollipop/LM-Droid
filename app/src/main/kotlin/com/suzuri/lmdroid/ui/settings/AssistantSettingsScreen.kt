package com.suzuri.lmdroid.ui.settings

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R

/**
 * Settings → アシスタント: registers this app as the device's assist app, so the system assist
 * gesture opens AssistActivity. On Android 10+ this goes through RoleManager's system picker;
 * older versions fall back to the legacy "Assist & voice input" settings screen, best-effort,
 * since not every OEM skin still exposes it. Which physical gesture (power-button long-press,
 * home long-press, a swipe, ...) actually triggers assist is a device/OEM setting this app has no
 * control over — registering here only makes the app *eligible* to be chosen.
 */
@Composable
fun AssistantSettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

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
                    if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
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
    }
}
