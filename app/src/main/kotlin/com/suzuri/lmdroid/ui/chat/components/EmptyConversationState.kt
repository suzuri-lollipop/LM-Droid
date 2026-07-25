package com.suzuri.lmdroid.ui.chat.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.ui.chat.SuggestionsUiState

private val SUGGESTION_KEYS = listOf(
    R.string.chat_suggestion_brainstorm,
    R.string.chat_suggestion_summarize,
    R.string.chat_suggestion_debug,
    R.string.chat_suggestion_explain,
)

// Suggestion rows (generated or fallback alike) are shown narrower than full width — it reads
// lighter-weight than a row stretched edge to edge. The loading skeleton matches that same width
// on every row (not tapered) so resolving to real content doesn't visibly jump, and so the
// skeleton itself doesn't read as "shrinking" as it goes down.
private const val SUGGESTION_WIDTH_FRACTION = 0.67f
private const val LOADING_PLACEHOLDER_COUNT = 3

/** The icon + greeting shown above the input bar for a brand-new, empty conversation. */
@Composable
fun EmptyConversationGreeting(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }

        Text(
            text = stringResource(R.string.chat_empty_greeting),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
    }
}

/**
 * Tappable example prompts shown below the input bar; tapping one fills the input with its text.
 * While [state] is [SuggestionsUiState.Loading] (an LLM call personalizing these from past
 * conversation topics — see ChatViewModel), a shimmering skeleton is shown instead of guessing at
 * content ahead of time. Only once that call fails or has nothing to work with (
 * [SuggestionsUiState.Fallback]) do the generic static starter prompts appear, and only on success
 * ([SuggestionsUiState.Generated]) do the personalized ones.
 */
@Composable
fun EmptyConversationSuggestions(
    onSuggestionClick: (String) -> Unit,
    state: SuggestionsUiState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is SuggestionsUiState.Loading -> SuggestionsLoadingPlaceholder(modifier = modifier)
        is SuggestionsUiState.Generated -> SuggestionRows(
            prompts = state.prompts,
            widthFraction = SUGGESTION_WIDTH_FRACTION,
            onSuggestionClick = onSuggestionClick,
            modifier = modifier,
        )
        is SuggestionsUiState.Fallback -> SuggestionRows(
            prompts = SUGGESTION_KEYS.map { stringResource(it) },
            widthFraction = SUGGESTION_WIDTH_FRACTION,
            onSuggestionClick = onSuggestionClick,
            modifier = modifier,
        )
    }
}

/** Rendered as flat, borderless tonal rows rather than Material's default outlined chip — the visible stroke read as dated. */
@Composable
private fun SuggestionRows(
    prompts: List<String>,
    widthFraction: Float,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        prompts.forEach { text ->
            Surface(
                onClick = { onSuggestionClick(text) },
                modifier = Modifier.fillMaxWidth(widthFraction),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    )
                }
            }
        }
    }
}

/** A pulsing skeleton standing in for the suggestion rows while they're still being generated. */
@Composable
private fun SuggestionsLoadingPlaceholder(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "suggestions_loading")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "suggestions_loading_alpha",
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(LOADING_PLACEHOLDER_COUNT) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(SUGGESTION_WIDTH_FRACTION)
                    .height(48.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)),
            )
        }
    }
}
