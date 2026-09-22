package com.suzuri.lmdroid.ui.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.db.ThinkingEffort

/** The label shown for each [ThinkingEffort] level, both in the model selector sheet (ModelSelectorButton) and the per-profile default editor (OpenAiCompatibleScreen). */
@Composable
fun ThinkingEffort.label(): String = when (this) {
    ThinkingEffort.OFF -> stringResource(R.string.chat_thinking_effort_off)
    ThinkingEffort.LOW -> stringResource(R.string.chat_thinking_effort_low)
    ThinkingEffort.MEDIUM -> stringResource(R.string.chat_thinking_effort_medium)
    ThinkingEffort.XHIGH -> stringResource(R.string.chat_thinking_effort_xhigh)
}
