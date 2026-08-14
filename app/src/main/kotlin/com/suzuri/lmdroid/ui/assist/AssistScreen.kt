package com.suzuri.lmdroid.ui.assist

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.model.rememberMarkdownState
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.character.CharacterModelType
import com.suzuri.lmdroid.ui.assist.components.NamePlate
import com.suzuri.lmdroid.ui.assist.components.NovelContinueIndicator
import com.suzuri.lmdroid.ui.assist.components.NovelMessageWindow
import com.suzuri.lmdroid.ui.assist.components.ThinkingDots
import com.suzuri.lmdroid.ui.assist.components.TypewriterText
import com.suzuri.lmdroid.ui.assist.components.rememberTypewriterState
import com.suzuri.lmdroid.ui.character.CharacterStage
import com.suzuri.lmdroid.ui.character.decodeDownsampled
import com.suzuri.lmdroid.ui.character.deriveCharacterState
import com.suzuri.lmdroid.ui.chat.components.rememberLocalVoiceInputState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The overlay shown by AssistActivity (launched via the system assist gesture — see
 * AndroidManifest's ACTION_ASSIST filter): starts listening immediately, shows the transcript live
 * as it's recognized, then sends it through [AssistViewModel] and streams the reply in place —
 * a bottom-sheet-style scrim over whatever app was already on screen, like Google Assistant,
 * rather than switching full-screen into this app.
 *
 * Layout switches on Settings → キャラクター: with no character configured (the default) it's the
 * original bottom sheet; otherwise the whole overlay becomes a novel-game-style stage —
 * background, character, name plate and message window (see [AssistStage]).
 */
@Composable
fun AssistScreen(
    viewModel: AssistViewModel,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val voiceUnavailableMessage = stringResource(R.string.chat_voice_input_unavailable)
    val voicePermissionDeniedMessage = stringResource(R.string.chat_voice_input_permission_denied)

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val voiceInputState = rememberLocalVoiceInputState(
        onResult = viewModel::onFinalTranscript,
        onPartialResult = viewModel::onPartialTranscript,
        onError = viewModel::onListeningError,
    )

    // Tracks whether we've attempted to start listening since the last trigger/reset.
    // Used to avoid showing "not detected" during the initial stabilization delay.
    var hasStartedListening by remember(uiState.triggerCount) { mutableStateOf(false) }

    fun beginListening() {
        hasStartedListening = true
        when {
            !voiceInputState.isAvailable -> viewModel.onListeningError(voiceUnavailableMessage)
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> voiceInputState.start(scope)
            else -> {} // Handled by permission launcher
        }
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) beginListening() else viewModel.onListeningError(voicePermissionDeniedMessage)
    }

    // Re-check permission and launch either recognition or permission request
    fun requestOrStartListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginListening()
        } else if (voiceInputState.isAvailable) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            viewModel.onListeningError(voiceUnavailableMessage)
        }
    }

    // Zero-tap start: the whole point of the power-button-long-press gesture is to go straight
    // into listening without also having to tap a mic button first.
    // We react to uiState.triggerCount so that subsequent external triggers (e.g. earphone button)
    // while the overlay is already open will restart the listening process.
    LaunchedEffect(uiState.triggerCount) {
        delay(300)
        requestOrStartListening()
    }

    val onMicClick = {
        viewModel.onAskFollowUp()
        requestOrStartListening()
    }

    if (uiState.characterSettings.modelType == CharacterModelType.NONE) {
        AssistBottomSheet(
            uiState = uiState,
            isListening = voiceInputState.isListening,
            hasStartedListening = hasStartedListening,
            onOpenApp = onOpenApp,
            onDismiss = onDismiss,
            onMicClick = onMicClick,
            onRetryListening = ::beginListening,
            modifier = modifier,
        )
    } else {
        AssistStage(
            uiState = uiState,
            isListening = voiceInputState.isListening,
            hasStartedListening = hasStartedListening,
            onOpenApp = onOpenApp,
            onDismiss = onDismiss,
            onMicClick = onMicClick,
            onRetryListening = ::beginListening,
            modifier = modifier,
        )
    }
}

/** The original bottom-sheet overlay — used whenever no character is configured, so nothing about the pre-character behavior changes. */
@Composable
private fun AssistBottomSheet(
    uiState: AssistUiState,
    isListening: Boolean,
    hasStartedListening: Boolean,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
    onMicClick: () -> Unit,
    onRetryListening: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Bounded rather than left to wrap-content: a long reply would otherwise grow the sheet
        // past the top of the screen (BottomCenter anchors its bottom edge, so the overflow was
        // silently clipped off-screen instead of ever being reachable). Capped short of the full
        // height so a sliver of the scrim stays visible, keeping the bottom-sheet feel.
        val maxSheetHeight = maxHeight * 0.9f

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding()
                // Absorbs taps so they don't fall through to the scrim's onDismiss behind it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                }
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Shows which (profile, model) is actually answering (see Settings →
                    // アシスタント) instead of a static label, falling back to it only when no
                    // chat profile is configured at all yet.
                    Text(
                        text = uiState.modelProfileName ?: stringResource(R.string.assist_title),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.assist_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Only this part scrolls — the drag handle/title/close button above stay pinned,
                // and weight(fill = false) keeps a short reply's sheet compact instead of always
                // stretching to maxSheetHeight.
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    when {
                        uiState.apiKeyMissing -> {
                            Text(
                                text = stringResource(R.string.chat_api_key_missing_message),
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onOpenApp) { Text(stringResource(R.string.chat_go_to_settings)) }
                        }
                        uiState.errorMessage != null -> {
                            Text(text = uiState.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(16.dp))
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                ListeningIndicator(
                                    isListening = false,
                                    onClick = onRetryListening,
                                    contentDescription = stringResource(R.string.assist_retry_listening),
                                )
                            }
                        }
                        else -> {
                            AssistConversationContent(
                                isListening = isListening,
                                isStarted = hasStartedListening,
                                isStreaming = uiState.isStreaming,
                                transcript = uiState.transcript,
                                hasSent = uiState.hasSent,
                                assistantText = uiState.assistantText,
                                isAssistantError = uiState.isAssistantError,
                                markdownEnabled = uiState.markdownEnabled,
                                onMicClick = onMicClick,
                            )

                            if (uiState.hasSent && !uiState.isStreaming) {
                                Spacer(Modifier.height(8.dp))
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    TextButton(onClick = onOpenApp) {
                                        Text(stringResource(R.string.assist_open_in_app))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The novel-game-style stage: background layer, character layer, then the UI layer (close button,
 * the user's own words, name plate + message window, the one mic control). Tapping the empty
 * stage dismisses the overlay like the sheet's scrim does; the message window absorbs taps (its
 * text handles the typewriter skip itself).
 */
@Composable
private fun AssistStage(
    uiState: AssistUiState,
    isListening: Boolean,
    hasStartedListening: Boolean,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
    onMicClick: () -> Unit,
    onRetryListening: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val characterState = deriveCharacterState(
        isListening = isListening,
        hasSent = uiState.hasSent,
        assistantText = uiState.assistantText,
        isStreaming = uiState.isStreaming,
        isSpeaking = uiState.isSpeaking,
        hasError = uiState.errorMessage != null || uiState.isAssistantError,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        StageBackground(backgroundPath = uiState.characterSettings.backgroundPath)

        CharacterStage(
            settings = uiState.characterSettings,
            characterState = characterState,
            lipSyncEnabled = uiState.characterSettings.lipSyncEnabled,
            onTap = null,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.assist_close),
                        tint = Color.White.copy(alpha = 0.9f),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // "This is what you said", small and right-aligned above the window — the message
            // window itself is the character's, so the user's line stays visually distinct.
            if (uiState.hasSent && uiState.transcript.isNotBlank()) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        text = uiState.transcript,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.92f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            NamePlate(name = uiState.modelProfileName ?: stringResource(R.string.assist_title))

            Spacer(Modifier.height(4.dp))

            NovelMessageWindow(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
            ) {
                StageWindowContent(
                    uiState = uiState,
                    isListening = isListening,
                    hasStartedListening = hasStartedListening,
                    onOpenApp = onOpenApp,
                )
            }

            Spacer(Modifier.height(16.dp))

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (uiState.errorMessage != null) {
                    ListeningIndicator(
                        isListening = false,
                        onClick = onRetryListening,
                        contentDescription = stringResource(R.string.assist_retry_listening),
                        onStage = true,
                    )
                } else if (!uiState.isStreaming) {
                    ListeningIndicator(
                        isListening = isListening,
                        onClick = onMicClick,
                        contentDescription = if (uiState.hasSent) {
                            stringResource(R.string.assist_ask_follow_up)
                        } else {
                            stringResource(R.string.assist_retry_listening)
                        },
                        onStage = true,
                    )
                }
            }

            if (uiState.hasSent && !uiState.isStreaming) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onOpenApp) {
                        Text(stringResource(R.string.assist_open_in_app))
                    }
                }
            }
        }
    }
}

/** What goes inside the stage's message window — mirrors the bottom sheet's state branches, but in the novel-window style (typewriter reveal, thinking dots). */
@Composable
private fun StageWindowContent(
    uiState: AssistUiState,
    isListening: Boolean,
    hasStartedListening: Boolean,
    onOpenApp: () -> Unit,
) {
    val typewriterState = rememberTypewriterState()
    val scrollState = rememberScrollState()
    val contentScroll = Modifier.verticalScroll(scrollState)

    // Keep the newest revealed text in view while the typewriter runs / the reply streams.
    LaunchedEffect(typewriterState.visibleChars, uiState.assistantText) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    when {
        uiState.apiKeyMissing -> {
            Column(modifier = contentScroll) {
                Text(
                    text = stringResource(R.string.chat_api_key_missing_message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenApp) { Text(stringResource(R.string.chat_go_to_settings)) }
            }
        }
        uiState.errorMessage != null -> {
            Text(
                text = uiState.errorMessage.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = contentScroll,
            )
        }
        !uiState.hasSent -> {
            val hintText = when {
                uiState.transcript.isNotBlank() -> uiState.transcript
                isListening -> stringResource(R.string.assist_listening_hint)
                // If we haven't even attempted to listen yet (during the initial delay),
                // show the hint instead of "not detected".
                !hasStartedListening -> stringResource(R.string.assist_listening_hint)
                else -> stringResource(R.string.assist_no_speech_detected)
            }
            TypewriterText(
                text = hintText,
                enabled = uiState.characterSettings.typewriterEnabled,
                typewriterState = typewriterState,
                style = MaterialTheme.typography.bodyLarge,
                color = if (uiState.transcript.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = contentScroll,
            )
        }
        uiState.isAssistantError -> {
            Text(
                text = uiState.assistantText.ifBlank { "…" },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = contentScroll,
            )
        }
        uiState.assistantText.isBlank() -> {
            ThinkingDots(modifier = contentScroll)
        }
        uiState.characterSettings.typewriterEnabled -> {
            Column(modifier = contentScroll.heightIn(max = STAGE_WINDOW_MAX_HEIGHT)) {
                TypewriterText(
                    text = uiState.assistantText,
                    enabled = true,
                    typewriterState = typewriterState,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (typewriterState.isComplete(uiState.assistantText) && !uiState.isStreaming) {
                    Spacer(Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        NovelContinueIndicator()
                    }
                }
            }
        }
        uiState.markdownEnabled -> {
            val markdownState = rememberMarkdownState(content = uiState.assistantText, retainState = true)
            Markdown(
                markdownState = markdownState,
                colors = markdownColor(text = MaterialTheme.colorScheme.onSurface),
                modifier = contentScroll.heightIn(max = STAGE_WINDOW_MAX_HEIGHT),
            )
        }
        else -> {
            Text(
                text = uiState.assistantText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = contentScroll.heightIn(max = STAGE_WINDOW_MAX_HEIGHT),
            )
        }
    }
}

/** The stage's background layer: the user's chosen image when set, otherwise a plain dark scrim — plus a light readability scrim over either. */
@Composable
private fun StageBackground(backgroundPath: String?) {
    // The raw Bitmap (rather than ImageBitmap) so it can be recycled when replaced.
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(backgroundPath) {
        val previous = bitmap
        bitmap = backgroundPath?.let { path ->
            withContext(Dispatchers.IO) { decodeDownsampled(path) }
        }
        previous?.let { previousBitmap ->
            if (bitmap !== previousBitmap) previousBitmap.recycle()
        }
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
    }
}

@Composable
private fun AssistConversationContent(
    isListening: Boolean,
    isStarted: Boolean,
    isStreaming: Boolean,
    transcript: String,
    hasSent: Boolean,
    assistantText: String,
    isAssistantError: Boolean,
    markdownEnabled: Boolean,
    onMicClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!hasSent) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) {
                ListeningIndicator(
                    isListening = isListening,
                    onClick = onMicClick,
                    contentDescription = stringResource(R.string.assist_retry_listening),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = when {
                        transcript.isNotBlank() -> transcript
                        isListening -> stringResource(R.string.assist_listening_hint)
                        // If we haven't even attempted to listen yet (during the initial delay),
                        // show the hint instead of "not detected".
                        !isStarted -> stringResource(R.string.assist_listening_hint)
                        else -> stringResource(R.string.assist_no_speech_detected)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (transcript.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            // Same bubble treatment as the main chat's own UserBubble (see MessageBubble.kt) —
            // right-aligned, primary-colored, rounded — so "this is what you said" reads
            // instantly from the shape alone, the same way it already does in the full app,
            // rather than introducing a new "あなた"/"アシスタント" label convention here.
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = transcript,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            when {
                isAssistantError -> Text(
                    text = assistantText.ifBlank { "…" },
                    color = MaterialTheme.colorScheme.error,
                )
                assistantText.isBlank() -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                markdownEnabled -> {
                    val markdownState = rememberMarkdownState(content = assistantText, retainState = true)
                    Markdown(
                        markdownState = markdownState,
                        colors = markdownColor(text = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> Text(text = assistantText, color = MaterialTheme.colorScheme.onSurface)
            }

            // Reuses the exact same mic indicator shown before the first message was sent
            // (rather than a separate button elsewhere), so there's only ever one mic control
            // on screen — tapping it asks a follow-up in the same conversation.
            if (!isStreaming) {
                Spacer(Modifier.height(16.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ListeningIndicator(
                        isListening = isListening,
                        onClick = onMicClick,
                        contentDescription = stringResource(R.string.assist_ask_follow_up),
                    )
                }
            }
        }
    }
}

/**
 * The one mic control shown throughout the whole overlay — pulses gently while actively
 * listening; tapping it (re)starts listening, whether that's the very first prompt, a retry after
 * silence/an error, or a follow-up after a reply. Reused everywhere rather than introducing a
 * second, smaller mic button, so there's only ever one mic affordance on screen at a time.
 * On the stage ([onStage] = true) it swaps the tonal surface for a dark disc with a bright ring
 * so it reads against an arbitrary background image instead of the sheet's surface.
 */
@Composable
private fun ListeningIndicator(
    isListening: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onStage: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "assist-listening")
    val scale by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(animation = tween(700, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "assist-listening-scale",
    )
    Box(
        modifier = modifier
            .size(64.dp)
            .scale(if (isListening) scale else 1f)
            .clip(CircleShape)
            .background(if (onStage) Color.Black.copy(alpha = 0.45f) else MaterialTheme.colorScheme.primaryContainer)
            .then(
                if (onStage) Modifier.border(2.dp, Color.White.copy(alpha = 0.75f), CircleShape) else Modifier,
            )
            .clickable(enabled = !isListening, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = contentDescription,
            tint = if (onStage) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(28.dp),
        )
    }
}

private val STAGE_WINDOW_MAX_HEIGHT = 240.dp
