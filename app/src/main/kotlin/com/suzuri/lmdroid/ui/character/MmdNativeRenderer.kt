package com.suzuri.lmdroid.ui.character

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The Phase-4 [CharacterRenderer]: hosts the C++ MMD engine/renderer (see mmd_jni.cpp) inside a
 * GLSurfaceView. Implements GLSurfaceView.Renderer directly — model parsing happens once on the
 * GL thread at surface creation (a one-off stall is acceptable there), texture decoding runs on
 * IO threads and uploads queue up for the next draw frame, and every frame advances animation,
 * physics and skinning natively before drawing.
 */
class MmdNativeRenderer(
    private val pmxPath: String,
    private val vmdPath: String?,
) : GLSurfaceView.Renderer, CharacterRenderer {

    private data class TextureUpload(val index: Int, val width: Int, val height: Int, val pixels: IntArray)

    private val handle: Long = MmdNative.nativeCreate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingTextures = ConcurrentLinkedQueue<TextureUpload>()

    @Volatile
    private var currentState = CharacterUiState.Idle

    @Volatile
    private var mouthOpen = 0f

    @Volatile
    var lipSyncEnabled = true

    /** Non-null when the model (or its GL init) failed to load — the stage falls back to nothing. */
    @Volatile
    var loadError: String? = null
        private set

    private var lastFrameNs = 0L

    override fun setState(state: CharacterUiState) {
        currentState = state
    }

    override fun setMouthOpen(value: Float) {
        mouthOpen = value
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (!MmdNative.nativeLoadModel(handle, pmxPath)) {
            loadError = MmdNative.nativeLastError(handle)
            return
        }
        vmdPath?.takeIf { File(it).exists() }?.let { path ->
            if (!MmdNative.nativeLoadMotion(handle, path)) {
                // A broken motion shouldn't kill the model — it still draws in its default pose
                // with procedural blink/lip sync.
                loadError = null
            }
        }
        decodeTexturesAsync()
        if (!MmdNative.nativeInitGl(handle)) {
            loadError = "GL init failed"
        }
    }

    private fun decodeTexturesAsync() {
        val count = MmdNative.nativeTextureCount(handle)
        for (index in 0 until count) {
            val path = MmdNative.nativeTexturePath(handle, index)
            if (path.isBlank()) continue
            scope.launch {
                val bitmap = decodeTextureBitmap(path) ?: return@launch
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                bitmap.recycle()
                pendingTextures.add(TextureUpload(index, width, height, pixels))
            }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        MmdNative.nativeResize(handle, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        while (true) {
            val upload = pendingTextures.poll() ?: break
            MmdNative.nativeSetTexture(handle, upload.index, upload.width, upload.height, upload.pixels)
        }
        if (loadError != null) return
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 1f / 60f else ((now - lastFrameNs) / 1e9f).coerceAtMost(0.1f)
        lastFrameNs = now
        MmdNative.nativeDrawFrame(handle, dt, currentState.ordinal, mouthOpen, lipSyncEnabled)
    }

    override fun release() {
        scope.cancel()
        MmdNative.nativeDestroy(handle)
    }

    private companion object {
        const val MAX_TEXTURE_DIMENSION = 1024

        /** Decodes a texture file down to [MAX_TEXTURE_DIMENSION] so 4K source art can't exhaust GL memory. */
        fun decodeTextureBitmap(path: String): Bitmap? {
            val file = File(path)
            if (!file.exists()) return null
            return runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sampleSize = 1
                while (bounds.outWidth / sampleSize > MAX_TEXTURE_DIMENSION ||
                    bounds.outHeight / sampleSize > MAX_TEXTURE_DIMENSION
                ) {
                    sampleSize *= 2
                }
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            }.getOrNull()
        }
    }
}
