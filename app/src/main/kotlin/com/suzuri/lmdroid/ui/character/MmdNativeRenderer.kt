package com.suzuri.lmdroid.ui.character

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.util.Log
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
 * GLSurfaceView. Model parsing happens once on the GL thread at surface creation, texture
 * decoding runs on IO threads and uploads queue for the next frame, and every frame advances
 * animation/physics/skinning natively before drawing.
 *
 * The GLSurfaceView only composites inside the translucent assist activity once the window is
 * switched to an opaque format (see AssistActivity) — a translucent window drops SurfaceView
 * child surfaces entirely.
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

    // The GUI-adjustable display range (Settings → キャラクター): zoom > 1 crops in, panX/panY
    // shift the framing. Written from the main thread by MmdSurface, read here each frame on
    // the GL thread — plain @Volatile floats are enough since they're read once per frame with
    // no cross-field consistency requirement (a torn read across two frames just shows one
    // stale-by-a-frame value, same tradeoff as mouthOpen/currentState below).
    @Volatile
    private var zoom = 1f

    @Volatile
    private var panX = 0f

    @Volatile
    private var panY = 0f

    fun setFraming(zoom: Float, panX: Float, panY: Float) {
        this.zoom = zoom
        this.panX = panX
        this.panY = panY
    }

    /** Non-null when the model (or its GL init) failed to load — the stage falls back to nothing. */
    @Volatile
    var loadError: String? = null
        private set

    // Destruction is deferred to the GL thread: release() only sets the flag, and the next
    // onDrawFrame performs nativeDestroy. Destroying from the Compose dispose path instead would
    // race a frame still in flight on the GL thread (use-after-free on the native handle).
    @Volatile
    private var releaseRequested = false

    @Volatile
    private var released = false

    override fun setState(state: CharacterUiState) {
        currentState = state
    }

    override fun setMouthOpen(value: Float) {
        mouthOpen = value
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (releaseRequested) return
        if (!MmdNative.nativeLoadModel(handle, pmxPath)) {
            val message = MmdNative.nativeLastError(handle)
            loadError = message
            Log.w(TAG, "loadModel failed: $message")
            return
        }
        vmdPath?.takeIf { File(it).exists() }?.let { path ->
            // A broken motion must not kill the model — it still draws in its default pose
            // with procedural blink/lip sync.
            MmdNative.nativeLoadMotion(handle, path)
        }
        decodeTexturesAsync()
        if (!MmdNative.nativeInitGl(handle)) {
            loadError = "GL init failed"
            Log.w(TAG, "GL init failed")
            return
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (releaseRequested) return
        MmdNative.nativeResize(handle, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (releaseRequested && !released) {
            MmdNative.nativeDestroy(handle)
            released = true
            return
        }
        if (released) return
        while (true) {
            val upload = pendingTextures.poll() ?: break
            MmdNative.nativeSetTexture(handle, upload.index, upload.width, upload.height, upload.pixels)
        }
        if (loadError != null) return
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 1f / 60f else ((now - lastFrameNs) / 1e9f).coerceAtMost(0.1f)
        lastFrameNs = now
        MmdNative.nativeDrawFrame(handle, dt, currentState.ordinal, mouthOpen, lipSyncEnabled, zoom, panX, panY)
    }

    override fun release() {
        scope.cancel()
        releaseRequested = true
    }

    private var lastFrameNs = 0L

    // One coroutine for all textures, not one each: a model can declare a dozen 2K sheets, and
    // decoding them in parallel would hold every bitmap plus its 4 MB pixel copy live at once.
    // An OOM here escapes the launch and takes down the process, so the peak is kept to one.
    private fun decodeTexturesAsync() {
        val count = MmdNative.nativeTextureCount(handle)
        val paths = (0 until count).map { MmdNative.nativeTexturePath(handle, it) }
        scope.launch {
            paths.forEachIndexed { index, path ->
                if (path.isBlank()) return@forEachIndexed
                val bitmap = decodeTextureBitmap(path)
                if (bitmap == null) {
                    // The material falls back to its flat diffuse color (uHasTex stays 0).
                    Log.w(TAG, "texture decode failed: $path")
                    return@forEachIndexed
                }
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                bitmap.recycle()
                pendingTextures.add(TextureUpload(index, width, height, pixels))
            }
        }
    }

    private companion object {
        const val TAG = "MmdNativeRenderer"
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
                BitmapFactory.decodeFile(
                    path,
                    // getPixels() always hands back straight (un-premultiplied) ARGB, which is
                    // what the native GL blend wants; decoding un-premultiplied too skips the
                    // premultiply/un-premultiply round trip, which quantizes RGB in soft-alpha
                    // regions (hair tips sit near alpha 0.4 and lose over a bit of precision).
                    BitmapFactory.Options().apply { inSampleSize = sampleSize; inPremultiplied = false },
                )
            }.getOrNull()
        }
    }
}
