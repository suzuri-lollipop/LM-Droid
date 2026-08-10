package com.suzuri.lmdroid.data.vosk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream

/**
 * Manages the Vosk [Model] instance and ensures its assets are copied from the APK to the
 * device's private storage exactly once. Shared between WakeWordService and LocalVoiceInput.
 */
class VoskRepository(private val context: Context) {
    private var model: Model? = null
    private val mutex = Mutex()

    suspend fun getModel(): Model? = mutex.withLock {
        model?.let { return it }
        val loaded = loadModel()
        model = loaded
        return loaded
    }

    private suspend fun loadModel(): Model? = withContext(Dispatchers.IO) {
        try {
            val destPath = File(context.filesDir, "vosk-model").absolutePath
            val modelDir = File(destPath)

            if (modelDir.exists() && !File(modelDir, "am").exists()) {
                Log.w(TAG, "Incomplete model directory found at $destPath, deleting...")
                modelDir.deleteRecursively()
            }

            if (!modelDir.exists()) {
                Log.d(TAG, "Starting manual asset copy for Vosk model...")
                try {
                    copyAssetDir("model", modelDir)
                    Log.d(TAG, "Model assets copied successfully to $destPath")
                } catch (e: Exception) {
                    Log.e(TAG, "Model asset copy failed", e)
                    return@withContext null
                }
            }

            Log.d(TAG, "Initializing Vosk Model from $destPath...")
            Model(destPath)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load Vosk model", e)
            null
        }
    }

    private fun copyAssetDir(assetDir: String, destDir: File) {
        val assetList = context.assets.list(assetDir) ?: run {
            Log.w(TAG, "No assets found in $assetDir")
            return
        }
        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        for (assetName in assetList) {
            val assetPath = "$assetDir/$assetName"
            val destFile = File(destDir, assetName)

            val subAssets = context.assets.list(assetPath)
            if (subAssets.isNullOrEmpty()) {
                copyAssetFile(assetPath, destFile)
            } else {
                copyAssetDir(assetPath, destFile)
            }
        }
    }

    private fun copyAssetFile(assetPath: String, destFile: File) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    companion object {
        private const val TAG = "VoskRepository"
    }
}
