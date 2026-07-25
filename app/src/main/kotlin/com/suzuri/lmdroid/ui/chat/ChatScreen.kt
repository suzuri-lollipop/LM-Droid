package com.suzuri.lmdroid.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.ui.chat.components.ChatInputBar
import com.suzuri.lmdroid.ui.chat.components.EmptyConversationGreeting
import com.suzuri.lmdroid.ui.chat.components.EmptyConversationSuggestions
import com.suzuri.lmdroid.ui.chat.components.ImagePreviewDialog
import com.suzuri.lmdroid.ui.chat.components.MessageBubble
import kotlinx.coroutines.launch

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
    val comingSoonMessage = stringResource(R.string.chat_feature_coming_soon)
    // Voice input isn't wired up yet — surfaced as "coming soon" rather than silently doing
    // nothing, so tapping it doesn't look broken.
    val onVoiceInput: () -> Unit = {
        coroutineScope.launch { snackbarHostState.showSnackbar(comingSoonMessage) }
    }
    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.onFileAttached(it) }
    }
    val onAttachFile: () -> Unit = { imagePickerLauncher.launch("image/*") }

    // Tapping any attachment thumbnail (staged or already sent) opens it full-screen — see
    // ImagePreviewDialog, rendered once at the bottom of this screen regardless of which
    // thumbnail triggered it.
    var previewImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    val onPreviewAttachment: (String) -> Unit = { path -> previewImagePath = path }

    LaunchedEffect(
        uiState.messages.size,
        uiState.messages.lastOrNull()?.content,
        uiState.messages.lastOrNull()?.reasoningContent,
    ) {
        // With reverseLayout = true below, index 0 is the newest message and sits at the bottom
        // of the viewport, so this single call keeps the growing tail pinned in place — no need
        // to reason about the pixel height of a message that's taller than the screen.
        if (uiState.messages.isNotEmpty()) {
            listState.scrollToItem(0)
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
                                    onRemoveAttachment = viewModel::onRemovePendingAttachment,
                                    onPreviewAttachment = onPreviewAttachment,
                                    onVoiceInput = onVoiceInput,
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
                                reverseLayout = true,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(uiState.messages.asReversed(), key = { it.id }) { message ->
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
                                    onRemoveAttachment = viewModel::onRemovePendingAttachment,
                                    onPreviewAttachment = onPreviewAttachment,
                                    onVoiceInput = onVoiceInput,
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
    }
}
