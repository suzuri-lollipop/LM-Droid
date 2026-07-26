package com.suzuri.lmdroid.ui.chat.components

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.db.MessageRole
import com.suzuri.lmdroid.ui.chat.MessageUiModel

@Composable
fun MessageBubble(
    message: MessageUiModel,
    onEditMessage: (Long, String) -> Unit,
    onRegenerate: (Long) -> Unit,
    onPreviewAttachment: (String) -> Unit,
    markdownEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (message.role == MessageRole.USER) {
        UserBubble(message, onEditMessage, onPreviewAttachment, markdownEnabled, modifier)
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
            when {
                message.isError -> Text(
                    text = message.content.ifBlank { "…" },
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                )
                message.content.isBlank() -> TypingIndicator(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
                // Renders headings/lists/links/**bold** etc. and gives fenced code blocks a
                // monospace font + distinct background with horizontal scroll for long lines,
                // instead of dumping raw markdown syntax as flat text.
                markdownEnabled -> {
                    // retainState = true keeps the last successfully-parsed markdown on screen
                    // while each streaming update reparses in the background — without it, every
                    // ~150ms content flush blanks the whole message to the library's default
                    // loading state before the new parse lands, which reads as constant flicker.
                    val markdownState = rememberMarkdownState(content = message.content, retainState = true)
                    Markdown(
                        markdownState = markdownState,
                        colors = markdownColor(text = MaterialTheme.colorScheme.onSurface),
                        padding = chatMarkdownPadding(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 4.dp),
                    )
                }
                else -> Text(
                    text = message.content,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                )
            }
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
    onPreviewAttachment: (String) -> Unit,
    markdownEnabled: Boolean,
    modifier: Modifier,
) {
    var isEditing by rememberSaveable(message.id) { mutableStateOf(false) }
    var editedText by rememberSaveable(message.id) { mutableStateOf(message.content) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        if (message.attachments.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                message.attachments.forEach { attachment ->
                    if (attachment.mimeType.startsWith("audio/")) {
                        AudioAttachmentChip(filePath = attachment.filePath)
                    } else {
                        AttachmentThumbnail(
                            filePath = attachment.filePath,
                            size = 96.dp,
                            onClick = { onPreviewAttachment(attachment.filePath) },
                        )
                    }
                }
            }
        }
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
            // A voice-message-only send has no text at all — an empty bubble here would just be
            // a blank rounded box sitting under the attachment with nothing in it.
            if (message.content.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (markdownEnabled) {
                        // retainState = true avoids flickering to a blank loading state on every
                        // edit-and-regenerate re-render (see the assistant-side comment above).
                        val markdownState = rememberMarkdownState(content = message.content, retainState = true)
                        // No modifier size overrides here: Markdown() defaults its own modifier to
                        // fillMaxSize(), which would force this bubble to the full 320.dp cap for
                        // every message. Passing the bare Modifier keeps it sized to content, like
                        // Text did.
                        Markdown(
                            markdownState = markdownState,
                            colors = markdownColor(
                                text = MaterialTheme.colorScheme.onPrimary,
                                codeBackground = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f),
                                inlineCodeBackground = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f),
                            ),
                            padding = userMarkdownPadding(),
                            modifier = Modifier,
                        )
                    } else {
                        Text(text = message.content, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
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

/**
 * The library's own default spacing (2.dp between paragraphs/headings) reads as one dense wall
 * of text for multi-paragraph LLM answers, so this widens the gaps between block elements.
 */
@Composable
private fun chatMarkdownPadding() = markdownPadding(
    block = 10.dp,
    listItemBottom = 6.dp,
    codeBlock = PaddingValues(12.dp),
)

/**
 * The renderer inserts a leading `block`-height spacer before every element — including the
 * first, and even when it's the only one. Inside the user bubble's own symmetric padding, that
 * extra gap only shows up above the text (never below), so a typical one-line prompt sits
 * visibly off-center. Zeroing `block` here removes that lopsided gap; a snug bubble doesn't need
 * chatMarkdownPadding()'s breathing room for long multi-paragraph answers anyway.
 */
@Composable
private fun userMarkdownPadding() = markdownPadding(block = 0.dp)

/** Three dots pulsing in sequence, shown in place of the assistant bubble before its first token arrives. */
@Composable
private fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing_indicator")
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, easing = LinearEasing, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(6.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                        shape = CircleShape,
                    ),
            )
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
