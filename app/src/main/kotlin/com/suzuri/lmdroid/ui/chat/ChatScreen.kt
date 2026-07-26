package com.suzuri.lmdroid.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.ui.chat.components.ChatInputBar
import com.suzuri.lmdroid.ui.chat.components.EmptyConversationGreeting
import com.suzuri.lmdroid.ui.chat.components.EmptyConversationSuggestions
import com.suzuri.lmdroid.ui.chat.components.ImagePreviewDialog
import com.suzuri.lmdroid.ui.chat.components.MessageBubble
import com.suzuri.lmdroid.ui.chat.components.SystemPromptDialog
import com.suzuri.lmdroid.ui.chat.components.rememberVoiceInputState
import kotlinx.coroutines.launch

// Far more than any realistic conversation's total scrollable height, so a single scrollBy call
// always clamps at the true bottom — see its use below for why Float.MAX_VALUE itself is avoided.
private const val LARGE_SCROLL_DELTA_PX = 100_000f

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.onFileAttached(it) }
    }
    val onAttachFile: () -> Unit = { imagePickerLauncher.launch("image/*") }

    // On-device speech-to-text for the mic button: recognized speech is fed straight into the
    // input field as plain text, so it works the same regardless of which model is selected for
    // chat — there's no "model doesn't support this" case, only "this device has no speech
    // recognition service" (voiceInputState.isAvailable), which some devices genuinely lack.
    val voiceUnavailableMessage = stringResource(R.string.chat_voice_input_unavailable)
    val voicePermissionDeniedMessage = stringResource(R.string.chat_voice_input_permission_denied)
    val voiceInputState = rememberVoiceInputState(
        onResult = viewModel::onVoiceInputResult,
        onError = { message -> coroutineScope.launch { snackbarHostState.showSnackbar(message) } },
    )

    fun hasRecordAudioPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // Both dictation (tap) and voice-message recording (long-press) need RECORD_AUDIO — whichever
    // one triggered the request is resumed once the user grants it.
    var pendingAfterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingAfterPermission?.invoke()
        } else {
            coroutineScope.launch { snackbarHostState.showSnackbar(voicePermissionDeniedMessage) }
        }
        pendingAfterPermission = null
    }
    val onVoiceInput: () -> Unit = {
        when {
            voiceInputState.isListening -> voiceInputState.stop()
            !voiceInputState.isAvailable ->
                coroutineScope.launch { snackbarHostState.showSnackbar(voiceUnavailableMessage) }
            hasRecordAudioPermission() -> voiceInputState.start()
            else -> {
                pendingAfterPermission = { voiceInputState.start() }
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    // Recording a voice message to send as audio (long-press on the mic) — a separate mechanism
    // from dictation above: this attaches the raw recording rather than converting it to text, so
    // an audio-input-capable model can listen to it directly. Unsupported models simply reject
    // the request with an error, surfaced the same way any other server error is.
    val onStartVoiceRecording: () -> Unit = {
        if (hasRecordAudioPermission()) {
            viewModel.onVoiceRecordingStart()
        } else {
            pendingAfterPermission = { viewModel.onVoiceRecordingStart() }
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val onStopVoiceRecording: () -> Unit = { viewModel.onVoiceRecordingStop() }

    // Tapping any attachment thumbnail (staged or already sent) opens it full-screen — see
    // ImagePreviewDialog, rendered once at the bottom of this screen regardless of which
    // thumbnail triggered it.
    var previewImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    val onPreviewAttachment: (String) -> Unit = { path -> previewImagePath = path }

    // The system-prompt button opens a dialog editing the app-wide instruction (see
    // SettingsRepository.systemPrompt) — its persisted value lives in uiState.systemPrompt, this
    // is just whether the dialog is currently showing.
    var isSystemPromptDialogOpen by rememberSaveable { mutableStateOf(false) }

    // Whether to keep auto-following new content to the bottom. "Is the last message merely
    // somewhere on screen" turned out too loose to gate this on directly: a tall streaming message
    // stays partially visible across a wide range of scroll positions, so it kept re-triggering
    // even after scrolling well away from the bottom. Instead this is turned off the moment the
    // user actually starts dragging/flinging the list (isScrollInProgress, guarded against our own
    // catch-up scroll below so that doesn't immediately undo itself), and back on once the last
    // message is on screen again.
    var autoScrollEnabled by remember { mutableStateOf(true) }
    var isCatchUpScroll by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !isCatchUpScroll) autoScrollEnabled = false
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisible == null || lastVisible.index == listState.layoutInfo.totalItemsCount - 1
        }.collect { atBottom -> if (atBottom) autoScrollEnabled = true }
    }

    LaunchedEffect(
        uiState.messages.size,
        uiState.messages.lastOrNull()?.content,
        uiState.messages.lastOrNull()?.thinkingTimeline,
    ) {
        // A large scroll delta clamps to the true end of the content regardless of how tall the
        // last (currently streaming) message is, keeping its growing tail in view — scrollToItem
        // alone would only show that message starting from its own top. Float.MAX_VALUE itself is
        // avoided here since LazyListState's internal scroll-distance math can misbehave at that
        // extreme (observed scrolling to the *top* instead of clamping at the bottom).
        if (uiState.messages.isNotEmpty() && autoScrollEnabled) {
            isCatchUpScroll = true
            try {
                listState.scrollBy(LARGE_SCROLL_DELTA_PX)
            } finally {
                isCatchUpScroll = false
            }
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SharedTransitionLayout(modifier = Modifier.fillMaxSize().imePadding()) {
            val sharedTransitionScope = this

            AnimatedContent(
                targetState = uiState.messages.isEmpty(),
                label = "chat_layout",
            ) { isEmpty ->
                val animatedVisibilityScope = this

                // The same key/modifier on the input bar in both branches below is what makes
                // SharedTransitionLayout smoothly interpolate its position and size ("ニュッと")
                // between the centered landing spot and the bottom-docked bar, instead of an
                // abrupt jump.
                with(sharedTransitionScope) {
                    val inputBarModifier = Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "chat_input_bar"),
                        animatedVisibilityScope = animatedVisibilityScope,
                    )

                    if (isEmpty) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            EmptyConversationGreeting()
                            Spacer(modifier = Modifier.height(20.dp))
                            if (uiState.apiKeyMissing) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.chat_api_key_missing_message))
                                    Button(
                                        onClick = onNavigateToSettings,
                                        modifier = Modifier.padding(top = 12.dp),
                                    ) {
                                        Text(stringResource(R.string.chat_go_to_settings))
                                    }
                                }
                            } else {
                                // Input bar first, prompt examples below it — tapping one just
                                // fills the (already-visible) input bar right above rather than
                                // a field the user hasn't seen yet.
                                ChatInputBar(
                                    input = uiState.input,
                                    isStreaming = uiState.isStreaming,
                                    onInputChange = viewModel::onInputChange,
                                    onSend = viewModel::onSend,
                                    onStop = viewModel::onStopGeneration,
                                    availableModels = uiState.availableModels,
                                    selectedModel = uiState.selectedModel,
                                    onSelectModel = viewModel::onSelectModel,
                                    pendingAttachments = uiState.pendingAttachments,
                                    onAttachFile = onAttachFile,
                                    onOpenSystemPrompt = { isSystemPromptDialogOpen = true },
                                    onRemoveAttachment = viewModel::onRemovePendingAttachment,
                                    onPreviewAttachment = onPreviewAttachment,
                                    isListening = voiceInputState.isListening,
                                    onVoiceInput = onVoiceInput,
                                    isRecordingVoiceMessage = uiState.isRecordingVoiceMessage,
                                    onStartVoiceRecording = onStartVoiceRecording,
                                    onStopVoiceRecording = onStopVoiceRecording,
                                    modifier = inputBarModifier.fillMaxWidth(),
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                                EmptyConversationSuggestions(
                                    onSuggestionClick = viewModel::onInputChange,
                                    state = uiState.suggestionsState,
                                )
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(uiState.messages, key = { it.id }) { message ->
                                    MessageBubble(
                                        message = message,
                                        onEditMessage = viewModel::onEditMessage,
                                        onRegenerate = viewModel::onRegenerateResponse,
                                        onPreviewAttachment = onPreviewAttachment,
                                        markdownEnabled = uiState.markdownEnabled,
                                    )
                                }
                            }

                            if (uiState.apiKeyMissing) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(stringResource(R.string.chat_api_key_missing_message))
                                    Button(
                                        onClick = onNavigateToSettings,
                                        modifier = Modifier.padding(top = 12.dp),
                                    ) {
                                        Text(stringResource(R.string.chat_go_to_settings))
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.chat_markdown_toggle_label),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = uiState.markdownEnabled,
                                        onCheckedChange = viewModel::onMarkdownEnabledChange,
                                    )
                                }
                                ChatInputBar(
                                    input = uiState.input,
                                    isStreaming = uiState.isStreaming,
                                    onInputChange = viewModel::onInputChange,
                                    onSend = viewModel::onSend,
                                    onStop = viewModel::onStopGeneration,
                                    availableModels = uiState.availableModels,
                                    selectedModel = uiState.selectedModel,
                                    onSelectModel = viewModel::onSelectModel,
                                    pendingAttachments = uiState.pendingAttachments,
                                    onAttachFile = onAttachFile,
                                    onOpenSystemPrompt = { isSystemPromptDialogOpen = true },
                                    onRemoveAttachment = viewModel::onRemovePendingAttachment,
                                    onPreviewAttachment = onPreviewAttachment,
                                    isListening = voiceInputState.isListening,
                                    onVoiceInput = onVoiceInput,
                                    isRecordingVoiceMessage = uiState.isRecordingVoiceMessage,
                                    onStartVoiceRecording = onStartVoiceRecording,
                                    onStopVoiceRecording = onStopVoiceRecording,
                                    modifier = inputBarModifier,
                                )
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))

        previewImagePath?.let { path ->
            ImagePreviewDialog(filePath = path, onDismiss = { previewImagePath = null })
        }

        if (isSystemPromptDialogOpen) {
            SystemPromptDialog(
                initialValue = uiState.systemPrompt,
                onSave = { value ->
                    viewModel.onSystemPromptChange(value)
                    isSystemPromptDialogOpen = false
                },
                onDismiss = { isSystemPromptDialogOpen = false },
            )
        }
    }
}
