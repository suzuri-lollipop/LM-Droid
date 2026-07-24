package com.suzuri.lmdroid.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.ui.settings.components.SettingsMenuRow

/** Top of the Settings drill-down: currently just "LLM設定", with room for more categories later. */
@Composable
fun SettingsRootScreen(onNavigateToLlmSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        SettingsMenuRow(
            title = stringResource(R.string.settings_llm_category_title),
            onClick = onNavigateToLlmSettings,
        )
    }
}
