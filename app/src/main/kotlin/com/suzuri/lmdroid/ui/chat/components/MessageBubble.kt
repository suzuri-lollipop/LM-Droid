package com.suzuri.lmdroid.ui.chat.components

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.db.MessageRole
import com.suzuri.lmdroid.ui.chat.MessageUiModel

@Composable
fun MessageBubble(
    message: MessageUiModel,
    onEditMessage: (Long, String) -> Unit,
    onRegenerate: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message.role == MessageRole.USER) {
        UserBubble(message, onEditMessage, modifier)
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (!message.reasoningContent.isNullOrBlank()) {
            ReasoningSection(
                messageId = message.id,
                reasoning = message.reasoningContent,
                isStillThinking = message.content.isBlank() && !message.isError,
            )
        }

        val hasFinalContent = message.content.isNotBlank() || message.reasoningContent.isNullOrBlank()
        if (hasFinalContent) {
            Text(
                text = message.content.ifBlank { "…" },
                color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
            )
        }
        if (message.content.isNotBlank()) {
            Row {
                CopyIconButton(text = message.content)
                IconButton(onClick = { onRegenerate(message.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.chat_regenerate),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** User turns get a clear, colored bubble; assistant turns render as flat text (see [MessageBubble]) — this mirrors how most modern AI chat UIs (e.g. Claude, ChatGPT) visually separate the two. */
@Composable
private fun UserBubble(
    message: MessageUiModel,
    onEditMessage: (Long, String) -> Unit,
    modifier: Modifier,
) {
    var isEditing by rememberSaveable(message.id) { mutableStateOf(false) }
    var editedText by rememberSaveable(message.id) { mutableStateOf(message.content) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        if (isEditing) {
            OutlinedTextField(
                value = editedText,
                onValueChange = { editedText = it },
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(),
            )
            Row {
                IconButton(
                    onClick = {
                        isEditing = false
                        editedText = message.content
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.chat_edit_cancel),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = {
                        isEditing = false
                        onEditMessage(message.id, editedText)
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.chat_edit_regenerate),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(text = message.content, color = MaterialTheme.colorScheme.onPrimary)
            }
            Row {
                IconButton(onClick = { isEditing = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.chat_edit_message),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CopyIconButton(text = message.content)
            }
        }
    }
}

@Composable
private fun CopyIconButton(text: String) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedLabel = stringResource(R.string.chat_copied)
    IconButton(
        onClick = {
            clipboardManager.setText(AnnotatedString(text))
            Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.ContentCopy,
            contentDescription = stringResource(R.string.chat_copy),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A tappable, collapsible "thinking" block, similar to how Claude/ChatGPT show a model's
 * chain-of-thought: collapsed by default (just a small header), the user taps it to reveal the
 * raw reasoning text. The header label reflects whether the model is still thinking or done.
 */
@Composable
private fun ReasoningSection(messageId: Long, reasoning: String, isStillThinking: Boolean) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isStillThinking) "考え中…" else "思考プロセス",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Text(
                text = reasoning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
