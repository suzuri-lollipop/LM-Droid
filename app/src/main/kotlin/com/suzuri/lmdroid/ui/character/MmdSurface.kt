package com.suzuri.lmdroid.ui.character

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Compose host for the MMD renderer: a transparent GLSurfaceView layered in the stage between
 * the background and the UI column. The GL clear color is (0,0,0,0) so the Compose layers
 * behind it show through. This only composites while the assist window is in opaque format
 * (AssistActivity switches it for character mode) — translucent windows drop SurfaceView child
 * surfaces. The renderer is keyed on the model/motion paths: swapping models rebuilds it, and
 * leaving the stage releases the native engine.
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
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        },
        modifier = modifier,
    )
}
