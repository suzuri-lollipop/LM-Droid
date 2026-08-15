package com.suzuri.lmdroid.ui.character

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Compose host for the MMD renderer: a transparent GLSurfaceView layered over the stage's
 * background/character layout. The surface is a media overlay with an alpha EGL config so the
 * GL clear color (0,0,0,0) lets the Compose layers behind it show through. The renderer is
 * keyed on the model/motion paths — swapping models rebuilds it, and leaving the stage
 * releases the native engine.
 */
@Composable
fun MmdSurface(
    pmxPath: String,
    vmdPath: String?,
    characterState: CharacterUiState,
    lipSyncEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val renderer = remember(pmxPath, vmdPath) { MmdNativeRenderer(pmxPath, vmdPath) }
    renderer.setState(characterState)
    renderer.lipSyncEnabled = lipSyncEnabled
    renderer.setMouthOpen(if (characterState == CharacterUiState.Speaking && lipSyncEnabled) 1f else 0f)

    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }

    AndroidView(
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setZOrderMediaOverlay(true)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        },
        modifier = modifier,
    )
}
