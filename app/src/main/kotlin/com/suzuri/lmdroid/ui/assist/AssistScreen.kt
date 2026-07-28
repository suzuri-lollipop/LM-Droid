package com.suzuri.lmdroid.ui.assist

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.model.rememberMarkdownState
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.ui.chat.components.rememberVoiceInputState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween

/**
 * The overlay shown by AssistActivity (launched via the system assist gesture — see
 * AndroidManifest's ACTION_ASSIST filter): starts listening immediately, shows the transcript live
 * as it's recognized, then sends it through [AssistViewModel] and streams the reply in place —
 * a bottom-sheet-style scrim over whatever app was already on screen, like Google Assistant,
 * rather than switching full-screen into this app.
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

    val voiceInputState = rememberVoiceInputState(
        onResult = viewModel::onFinalTranscript,
        onPartialResult = viewModel::onPartialTranscript,
        onError = viewModel::onListeningError,
    )

    fun hasRecordAudioPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) voiceInputState.start() else viewModel.onListeningError(voicePermissionDeniedMessage)
    }

    fun beginListening() {
        when {
            !voiceInputState.isAvailable -> viewModel.onListeningError(voiceUnavailableMessage)
            hasRecordAudioPermission() -> voiceInputState.start()
            else -> recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Zero-tap start: the whole point of the power-button-long-press gesture is to go straight
    // into listening without also having to tap a mic button first.
    // We react to uiState.triggerCount so that subsequent external triggers (e.g. earphone button)
    // while the overlay is already open will restart the listening process.
    LaunchedEffect(uiState.triggerCount) { beginListening() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
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
                    Text(stringResource(R.string.assist_title), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.assist_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

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
                                onClick = ::beginListening,
                                contentDescription = stringResource(R.string.assist_retry_listening),
                            )
                        }
                    }
                    else -> {
                        AssistConversationContent(
                            isListening = voiceInputState.isListening,
                            isStreaming = uiState.isStreaming,
                            transcript = uiState.transcript,
                            hasSent = uiState.hasSent,
                            assistantText = uiState.assistantText,
                            isAssistantError = uiState.isAssistantError,
                            markdownEnabled = uiState.markdownEnabled,
                            onMicClick = {
                                viewModel.onAskFollowUp()
                                beginListening()
                            },
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

@Composable
private fun AssistConversationContent(
    isListening: Boolean,
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
                        else -> stringResource(R.string.assist_no_speech_detected)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (transcript.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Text(
                text = transcript,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
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
 * silence/an error, or a follow-up after a reply. Reused as-is everywhere rather than introducing
 * a second, smaller mic button, so there's only ever one mic affordance on screen at a time.
 */
@Composable
private fun ListeningIndicator(
    isListening: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
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
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(enabled = !isListening, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(28.dp),
        )
    }
}
