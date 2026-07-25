package com.suzuri.lmdroid.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.ui.settings.components.SettingsMenuRow

/** Lists LLM providers; currently just the OpenAI-compatible one, with room for more later. */
@Composable
fun ApiSettingsScreen(onNavigateToOpenAiCompatible: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        SettingsMenuRow(
            title = stringResource(R.string.settings_openai_compatible_title),
            onClick = onNavigateToOpenAiCompatible,
        )
    }
}
