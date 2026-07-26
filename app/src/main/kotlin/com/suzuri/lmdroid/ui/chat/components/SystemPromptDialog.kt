package com.suzuri.lmdroid.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.ui.chat.SystemPromptOptionUiModel

/**
 * Picks which saved system prompts are active for every conversation — zero or more at once.
 * Toggling a checkbox applies immediately without closing the dialog; creating, editing, or
 * deleting prompts happens in Settings → システムプロンプト, reached via [onManage]. Opened from
 * the button next to the attach button in [ChatInputBar].
 */
@Composable
fun SystemPromptDialog(
    prompts: List<SystemPromptOptionUiModel>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_system_prompt_title)) },
        text = {
            Column {
                if (prompts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_system_prompt_empty_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    prompts.forEach { prompt ->
                        SystemPromptOptionRow(
                            label = prompt.name,
                            checked = prompt.id in selectedIds,
                            onClick = { onToggle(prompt.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onManage() }) {
                Text(stringResource(R.string.system_prompt_manage))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.system_prompt_close))
            }
        },
    )
}

@Composable
private fun SystemPromptOptionRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onClick() })
        Text(label)
    }
}
