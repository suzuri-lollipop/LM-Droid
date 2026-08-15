package com.suzuri.lmdroid.ui.assist.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay

/** How far the typewriter reveal has progressed — hoisted so the caller can tell when the text is fully revealed (e.g. to show the ▼ marker) and skip it programmatically. */
@Stable
class TypewriterState internal constructor() {
    var visibleChars by mutableIntStateOf(0)
        private set

    fun isComplete(text: String): Boolean = visibleChars >= text.length

    fun skip(text: String) {
        visibleChars = text.length
    }

    internal fun advance() {
        visibleChars++
    }

    internal fun clampTo(length: Int) {
        if (visibleChars > length) visibleChars = length
    }

    internal fun showAll(length: Int) {
        visibleChars = length
    }
}

@Composable
fun rememberTypewriterState(): TypewriterState = remember { TypewriterState() }

/** Reveals text one character at a time, like a novel game's message window.
 *
 * The counter deliberately survives [text] changes unreset (the state is remembered outside this
 * composable): the assistant reply *grows* as it streams, and restarting from zero on every token
 * would replay the whole window each update. A genuinely new reply resets naturally — the send()
 * path clears the text to "" first, and [TypewriterState.clampTo] pulls the counter back down
 * with it. While the animation is running, tapping the text skips straight to the full text. */
@Composable
fun TypewriterText(
    text: String,
    enabled: Boolean,
    typewriterState: TypewriterState,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(text, enabled) {
        if (!enabled) {
            typewriterState.showAll(text.length)
            return@LaunchedEffect
        }
        typewriterState.clampTo(text.length)
        while (!typewriterState.isComplete(text)) {
            typewriterState.advance()
            delay(TYPEWRITER_CHAR_DELAY_MS)
        }
    }
    Text(
        text = text.take(typewriterState.visibleChars),
        style = style,
        color = color,
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = !typewriterState.isComplete(text),
            onClick = { typewriterState.skip(text) },
        ),
    )
}

/** The framed, semi-transparent message window at the bottom of the stage — content is a slot so error/api-key states can put buttons inside the same frame. */
@Composable
fun NovelMessageWindow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 6.dp,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
    ) {
        Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            content()
        }
    }
}

/** The small name tag above the window — shows who's speaking (the active model profile's name doubles as the character name). Width-capped so a long profile name can't stretch across the whole stage. */
@Composable
fun NamePlate(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 240.dp),
        )
    }
}

/** The bouncing ▼ shown once a message is fully revealed, prompting a follow-up tap — the classic novel-game "continue" marker. */
@Composable
fun NovelContinueIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "novel-continue")
    val bounce by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(animation = tween(500, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "novel-continue-bounce",
    )
    Icon(
        imageVector = Icons.Filled.KeyboardArrowDown,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = bounce.dp),
    )
}

/** Animated "…" shown in the message window while the reply is still empty (the model is thinking). */
@Composable
fun ThinkingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thinking-dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3.999f,
        animationSpec = infiniteRepeatable(animation = tween(1400, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "thinking-dots-phase",
    )
    Text(
        text = ".".repeat(phase.toInt() + 1),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

private const val TYPEWRITER_CHAR_DELAY_MS = 33L
