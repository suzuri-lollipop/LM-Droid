package com.suzuri.lmdroid.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.ui.settings.components.SettingsMenuRow

/** Top of the Settings drill-down: "API設定", "システム", "Web検索", "位置情報" and "設定をエクスポート", with room for more categories later. */
@Composable
fun SettingsRootScreen(
    onNavigateToApiSettings: () -> Unit,
    onNavigateToSystem: () -> Unit,
    onNavigateToWebSearch: () -> Unit,
    onNavigateToLocation: () -> Unit,
    onExportSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SettingsMenuRow(
            title = stringResource(R.string.settings_api_category_title),
            onClick = onNavigateToApiSettings,
        )
        SettingsMenuRow(
            title = stringResource(R.string.settings_system_category_title),
            onClick = onNavigateToSystem,
        )
        SettingsMenuRow(
            title = stringResource(R.string.settings_websearch_category_title),
            onClick = onNavigateToWebSearch,
        )
        SettingsMenuRow(
            title = stringResource(R.string.settings_location_category_title),
            onClick = onNavigateToLocation,
        )
        SettingsMenuRow(
            title = stringResource(R.string.settings_export_title),
            onClick = onExportSettings,
        )
    }
}
