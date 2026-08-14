package com.suzuri.lmdroid.ui.character

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.data.character.CharacterModelType
import com.suzuri.lmdroid.data.character.CharacterSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The character layer of the novel-game-style assistant stage (see AssistScreen's AssistStage).
 * So far only the static standing sprite is implemented; LIVE2D and MMD hosts (GLSurfaceView via
 * AndroidView, driving a CharacterRenderer over JNI) slot in here in later phases of the
 * character plan — that's why this dispatches on [CharacterSettings.modelType] instead of
 * drawing the sprite unconditionally.
 */
@Composable
fun CharacterStage(
    settings: CharacterSettings,
    characterState: CharacterUiState,
    lipSyncEnabled: Boolean,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        when (settings.modelType) {
            CharacterModelType.STATIC -> StaticSprite(
                imagePath = settings.modelPath,
                scale = settings.scale,
                characterState = characterState,
                lipSyncEnabled = lipSyncEnabled,
                onTap = onTap,
            )
            CharacterModelType.NONE,
            CharacterModelType.LIVE2D,
            CharacterModelType.MMD -> Unit // No renderer wired up yet (NONE by definition).
        }
    }
}

/**
 * The Phase-2 "renderer": a single standing image anchored bottom-center like a novel game's
 * sprite. State shows through lightweight motion rather than model swaps — slow breathing while
 * idle, a faster talking pulse while the TTS voice is playing (the pseudo lip-sync a still image
 * can manage; a real mouth parameter lands with the Live2D/MMD renderers) — plus a bounce on tap.
 */
@Composable
private fun StaticSprite(
    imagePath: String?,
    scale: Float,
    characterState: CharacterUiState,
    lipSyncEnabled: Boolean,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // The raw Bitmap (rather than ImageBitmap) so it can be recycled when replaced/disposed.
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(imagePath) {
        val previous = bitmap
        bitmap = imagePath?.let { path ->
            withContext(Dispatchers.IO) { decodeDownsampled(path) }
        }
        previous?.let { previousBitmap ->
            // Only recycle once it's no longer the one being displayed (a failed re-decode
            // leaves the old bitmap in place, and recycling it would draw from freed pixels).
            if (bitmap !== previousBitmap) previousBitmap.recycle()
        }
    }
    DisposableEffect(Unit) {
        onDispose { bitmap?.recycle() }
    }

    val current = bitmap ?: return

    val transition = rememberInfiniteTransition(label = "character-stage")
    val breathing by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.012f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "character-breathing",
    )
    val talkingPulse by transition.animateFloat(
        initialValue = 0.99f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(animation = tween(240, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "character-talking",
    )
    val isTalking = characterState == CharacterUiState.Speaking && lipSyncEnabled

    // Tap bounce: a quick overshoot scale driven by Animatable, keyed by how many taps landed.
    val tapScale = remember { androidx.compose.animation.core.Animatable(1f) }
    val scope = rememberCoroutineScope()

    Image(
        bitmap = current.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .heightIn(max = 480.dp)
            .scale(scale * breathing * (if (isTalking) talkingPulse else 1f) * tapScale.value)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    onTap?.invoke()
                    scope.launch {
                        tapScale.snapTo(0.96f)
                        tapScale.animateTo(1f, animationSpec = tween(300, easing = FastOutSlowInEasing))
                    }
                },
            ),
    )
}

/** Decodes [path] down to at most ~2048px per side so a huge camera/store photo can't blow the UI's texture budget. Shared with the stage's background layer (see AssistScreen). */
internal fun decodeDownsampled(path: String): Bitmap? {
    val file = File(path)
    if (!file.exists()) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_DIMENSION || bounds.outHeight / sampleSize > MAX_DIMENSION) {
            sampleSize *= 2
        }
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    }.getOrNull()
}

private const val MAX_DIMENSION = 2048
