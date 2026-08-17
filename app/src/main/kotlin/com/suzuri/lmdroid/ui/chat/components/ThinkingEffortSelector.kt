package com.suzuri.lmdroid.ui.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.db.ThinkingEffort

/** The label shown for each [ThinkingEffort] level, both in this dropdown and the per-profile default editor (OpenAiCompatibleScreen). */
@Composable
fun ThinkingEffort.label(): String = when (this) {
    ThinkingEffort.OFF -> stringResource(R.string.chat_thinking_effort_off)
    ThinkingEffort.LOW -> stringResource(R.string.chat_thinking_effort_low)
    ThinkingEffort.MEDIUM -> stringResource(R.string.chat_thinking_effort_medium)
    ThinkingEffort.XHIGH -> stringResource(R.string.chat_thinking_effort_xhigh)
}

/**
 * A brain-icon button (same toolbar slot the old on/off toggle occupied) that opens a dropdown to
 * pick how hard a reasoning-capable model should think — see [ThinkingEffort]. Follows
 * [ModelSelectorButton]'s DropdownMenu pattern rather than a modal bottom sheet, matching this
 * app's existing compact-picker convention.
 */
@Composable
fun ThinkingEffortButton(
    effort: ThinkingEffort,
    onSelect: (ThinkingEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.Psychology,
                contentDescription = stringResource(R.string.chat_thinking_effort_label, effort.label()),
                tint = if (effort == ThinkingEffort.OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThinkingEffort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    trailingIcon = if (option == effort) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
