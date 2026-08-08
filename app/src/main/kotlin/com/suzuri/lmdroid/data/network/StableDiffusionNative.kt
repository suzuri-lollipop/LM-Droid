package com.suzuri.lmdroid.data.network

import android.graphics.Bitmap

/**
 * JNI bridge for stable-diffusion.cpp.
 */
class StableDiffusionNative {
    companion object {
        init {
            System.loadLibrary("sd_jni")
        }
    }

    /**
     * Loads the model from the given path.
     * @param modelPath Path to the .safetensors or .ckpt file.
     * @return A pointer to the loaded model context, or 0 if failed.
     */
    external fun loadModel(modelPath: String): Long

    /**
     * Generates an image using the loaded model.
     * Supports both T2I and I2I.
     * @param context Pointer returned by [loadModel].
     * @param params Generation parameters.
     * @param onProgress Callback for progress updates (0.0 to 1.0).
     * @return Generated bitmap, or null if failed.
     */
    external fun generateImage(
        context: Long,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        seed: Long,
        sampleSteps: Int,
        baseImage: Bitmap?,
        denoisingStrength: Float,
        onProgress: (Float) -> Unit
    ): Bitmap?

    /**
     * Frees the model context.
     * @param context Pointer returned by [loadModel].
     */
    external fun freeModel(context: Long)
}
