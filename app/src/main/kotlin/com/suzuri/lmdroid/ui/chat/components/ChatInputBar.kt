package com.suzuri.lmdroid.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.db.ModelOptionRow
import com.suzuri.lmdroid.data.db.ThinkingEffort
import com.suzuri.lmdroid.data.settings.SelectedModel
import com.suzuri.lmdroid.ui.chat.PendingAttachmentUiModel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The composer: the message text field alone on its own full-width line (so it isn't squeezed
 * between a row of icons), then — below it — a row of staged image/voice-message previews (when
 * any are attached) and a chip for [forcedSkillName] (when the user explicitly picked a skill to
 * force into the next message, see SkillDialog), then a single toolbar row with every action:
 * the "+" add button (which opens a bottom sheet of the former attach-file / system-prompt /
 * skill buttons, Claude-style), the model switcher (with the 思考/thinking effort selector and
 * 記憶/memory toggle right next to it, since they're per-model concerns), mic, and send/stop.
 * The mic button is
 * dual-purpose: a quick tap dictates speech to text (see [onVoiceInput]), while pressing and
 * holding records a voice message to attach and send as audio (see [onStartVoiceRecording]/
 * [onStopVoiceRecording]) — mirroring how voice-message apps use the same gesture split.
 * [onOpenSystemPrompt] opens a dialog (see SystemPromptDialog) to edit the app-wide system
 * prompt; [onOpenSkill] opens the analogous dialog for skills (see SkillDialog). Both of those,
 * plus [onAttachFile], are now reached through the single "+" button's bottom sheet rather than
 * as their own toolbar icons.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    // See AppSettings.thinkingEffort — shown as a brain icon right next to the model switcher
    // since it's a per-model concern (only reasoning-capable models like Qwen3.8/Gemma act on it).
    thinkingEffort: ThinkingEffort,
    onThinkingEffortChange: (ThinkingEffort) -> Unit,
    // See AppSettings.memoryEnabled — shown right next to the thinking effort selector since it's
    // also a per-model concern (only models with persistent-memory support, e.g. Qwen3.8, act on it).
    memoryEnabled: Boolean,
    onMemoryEnabledChange: (Boolean) -> Unit,
    // See AppSettings.thinkingBudget — shown right next to the memory toggle since it's also a
    // per-model concern (only reasoning-capable models act on it).
    thinkingBudget: Int,
    onThinkingBudgetChange: (Int) -> Unit,
    pendingAttachments: List<PendingAttachmentUiModel>,
    onAttachFile: () -> Unit,
    onOpenSystemPrompt: () -> Unit,
    onOpenSkill: () -> Unit,
    // The skill (if any) the user explicitly picked via SkillDialog's "使う" action to force into
    // just the next message — shown as a removable chip, distinct from pendingAttachments below.
    forcedSkillName: String?,
    onClearForcedSkill: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onPreviewAttachment: (String) -> Unit,
    isListening: Boolean,
    onVoiceInput: () -> Unit,
    isRecordingVoiceMessage: Boolean,
    onStartVoiceRecording: () -> Unit,
    onStopVoiceRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The single "+" (Claude-style) add button's bottom sheet — consolidates the former
    // attach-file / system-prompt / skill toolbar buttons behind one entry point. Kept here
    // (not in the caller) so ChatScreen's two call sites need no signature changes.
    var isPlusMenuOpen by rememberSaveable { mutableStateOf(false) }
    val plusMenuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Column {
            // A borderless TextField (its own container/indicator hidden) sitting inside the pill
            // Surface above, rather than the default boxed OutlinedTextField look — alone on its
            // own line so it isn't squeezed between the icon buttons below.
            TextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
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

            if (forcedSkillName != null) {
                Row(
                    modifier = Modifier
                        .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.skill_forced_chip_label, forcedSkillName),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            IconButton(onClick = onClearForcedSkill, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.skill_forced_chip_remove),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            // One toolbar row for every action: the "+" add button and the model switcher
            // cluster on the left, mic/send on the right, with a flexible gap between them.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A single "+" (Claude-style) button that opens a bottom sheet offering the
                // former three separate toolbar actions: attach file, system prompt, skill.
                IconButton(onClick = { isPlusMenuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.chat_plus_menu),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (availableModels.isNotEmpty()) {
                    // A hard cap (rather than a weight-based one) so a long profile name's own
                    // maxLines=1 + TextOverflow.Ellipsis actually has a bounded width to shrink
                    // into — weight(fill = false) doesn't work for this: an unfilled weighted
                    // child is placed at its own (small) measured width, not its full allotted
                    // share, so it doesn't reserve any trailing gap and the following Spacer ends
                    // up right next to it instead of at the row's true right edge.
                    ModelSelectorButton(
                        options = availableModels,
                        selected = selectedModel,
                        onSelect = onSelectModel,
                        modifier = Modifier
                            .widthIn(max = 120.dp)
                            .padding(start = 4.dp),
                    )
                    ThinkingEffortButton(effort = thinkingEffort, onSelect = onThinkingEffortChange)
                    IconToggleButton(checked = memoryEnabled, onCheckedChange = onMemoryEnabledChange) {
                        Icon(
                            imageVector = Icons.Filled.Memory,
                            contentDescription = stringResource(R.string.chat_memory_toggle_label),
                            tint = if (memoryEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    ThinkingBudgetButton(budget = thinkingBudget, onChange = onThinkingBudgetChange)
                }

                Spacer(modifier = Modifier.weight(1f))

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
        }
    }

    // The "+" add button's bottom sheet — slides up from the bottom and offers the three former
    // toolbar actions as labeled rows. Selecting one dismisses the sheet and then performs the
    // same action the old button did (picker / system-prompt dialog / skill dialog).
    if (isPlusMenuOpen) {
        ModalBottomSheet(
            onDismissRequest = { isPlusMenuOpen = false },
            sheetState = plusMenuSheetState,
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                PlusSheetItem(
                    icon = Icons.Filled.AttachFile,
                    label = stringResource(R.string.chat_attach_file),
                    onClick = { isPlusMenuOpen = false; onAttachFile() },
                )
                PlusSheetItem(
                    icon = Icons.Filled.Tune,
                    label = stringResource(R.string.chat_system_prompt_title),
                    onClick = { isPlusMenuOpen = false; onOpenSystemPrompt() },
                )
                PlusSheetItem(
                    icon = Icons.Filled.Extension,
                    label = stringResource(R.string.chat_skill_title),
                    onClick = { isPlusMenuOpen = false; onOpenSkill() },
                )
            }
        }
    }
}

/**
 * A single tappable row in the "+" add button's bottom sheet: a leading [icon] plus a [label],
 * full-width and vertically centered. Selecting it calls [onClick].
 */
@Composable
private fun PlusSheetItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 20.dp),
        )
    }
}
