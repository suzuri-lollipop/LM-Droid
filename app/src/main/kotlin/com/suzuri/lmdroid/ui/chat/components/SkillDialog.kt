package com.suzuri.lmdroid.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.ui.chat.SkillOptionUiModel

/**
 * Picks which saved skills are active (advertised to the model as a name/description catalog it
 * can pull from on its own, via the "use_skill" tool — see ConversationRepository) — zero or more
 * at once, toggled with the checkbox, applying immediately without closing the dialog. Tapping
 * "使う" instead forces that skill into just the next message the user sends (see
 * ChatViewModel.onForceSkillForNextMessage), the explicit counterpart to the model's own
 * discovery — regardless of whether the skill is even in the active/checked set. Creating,
 * editing, or deleting skills happens in Settings → スキル, reached via [onManage]. Opened from the
 * button next to the system-prompt button in [ChatInputBar].
 */
@Composable
fun SkillDialog(
    skills: List<SkillOptionUiModel>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onUseNow: (Long) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_skill_title)) },
        text = {
            Column {
                if (skills.isEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_skill_empty_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    skills.forEach { skill ->
                        SkillOptionRow(
                            name = skill.name,
                            description = skill.description,
                            checked = skill.id in selectedIds,
                            onToggle = { onToggle(skill.id) },
                            onUseNow = { onUseNow(skill.id); onDismiss() },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onManage() }) {
                Text(stringResource(R.string.skill_manage))
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
private fun SkillOptionRow(
    name: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit,
    onUseNow: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onUseNow) {
            Text(stringResource(R.string.skill_use_now))
        }
    }
}
