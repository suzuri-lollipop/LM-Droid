package com.suzuri.lmdroid.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.db.ModelOptionRow
import com.suzuri.lmdroid.data.settings.SelectedModel
import com.suzuri.lmdroid.ui.chat.PendingAttachmentUiModel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The composer: a row of staged image/voice-message previews (when any are attached) above a
 * rounded box with the file-attach button, a system-prompt button, message text field, mic button
 * and send button on top; below that (when there's a model to switch between) a divider and the
 * model switcher. The mic button is dual-purpose: a quick tap dictates speech to text (see
 * [onVoiceInput]), while pressing and holding records a voice message to attach and send as audio
 * (see [onStartVoiceRecording]/[onStopVoiceRecording]) — mirroring how voice-message apps use the
 * same gesture split. [onOpenSystemPrompt] opens a dialog (see SystemPromptDialog) to edit the
 * app-wide system prompt.
 */
@Composable
fun ChatInputBar(
    input: String,
    isStreaming: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    availableModels: List<ModelOptionRow>,
    selectedModel: SelectedModel?,
    onSelectModel: (ModelOptionRow) -> Unit,
    pendingAttachments: List<PendingAttachmentUiModel>,
    onAttachFile: () -> Unit,
    onOpenSystemPrompt: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onPreviewAttachment: (String) -> Unit,
    isListening: Boolean,
    onVoiceInput: () -> Unit,
    isRecordingVoiceMessage: Boolean,
    onStartVoiceRecording: () -> Unit,
    onStopVoiceRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onAttachFile) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = stringResource(R.string.chat_attach_file),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenSystemPrompt) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.chat_system_prompt_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // A borderless TextField (its own container/indicator hidden) sitting inside the
                // pill Surface above, rather than the default boxed OutlinedTextField look.
                TextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                    enabled = !isStreaming,
                    maxLines = 6,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )

                // A plain Box (not IconButton) so a custom tap-vs-long-press gesture can be laid
                // on top without fighting IconButton's own built-in click handling.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown()
                                val releasedQuickly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    waitForUpOrCancellation()
                                } != null
                                if (releasedQuickly) {
                                    onVoiceInput()
                                } else {
                                    onStartVoiceRecording()
                                    waitForUpOrCancellation()
                                    onStopVoiceRecording()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = stringResource(R.string.chat_voice_input),
                        tint = if (isListening || isRecordingVoiceMessage) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                if (isStreaming) {
                    Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 2.dp)
                        IconButton(onClick = onStop) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = stringResource(R.string.chat_stop),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                } else {
                    val canSend = input.isNotBlank() || pendingAttachments.isNotEmpty()
                    IconButton(
                        onClick = onSend,
                        enabled = canSend,
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                color = if (canSend) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = CircleShape,
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.chat_send),
                            tint = if (canSend) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            if (pendingAttachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pendingAttachments.forEach { attachment ->
                        if (attachment.mimeType.startsWith("audio/")) {
                            AudioAttachmentChip(
                                filePath = attachment.filePath,
                                onRemove = { onRemoveAttachment(attachment.id) },
                            )
                        } else {
                            AttachmentThumbnail(
                                filePath = attachment.filePath,
                                size = 56.dp,
                                onRemove = { onRemoveAttachment(attachment.id) },
                                onClick = { onPreviewAttachment(attachment.filePath) },
                            )
                        }
                    }
                }
            }

            if (availableModels.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModelSelectorButton(
                        options = availableModels,
                        selected = selectedModel,
                        onSelect = onSelectModel,
                    )
                }
            }
        }
    }
}
