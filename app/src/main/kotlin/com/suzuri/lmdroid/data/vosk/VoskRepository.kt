package com.suzuri.lmdroid.data.vosk

import android.content.Context
import android.util.Log
import com.suzuri.lmdroid.data.stt.SpeechModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream

/**
 * Manages Vosk [Model] instances for different models.
 */
class VoskRepository(private val context: Context) {
    private val models = mutableMapOf<String, Model>()
    private val mutex = Mutex()

    suspend fun getModel(speechModel: SpeechModel): Model? = mutex.withLock {
        models[speechModel.id]?.let { return it }
        val loaded = loadModel(speechModel)
        if (loaded != null) {
            models[speechModel.id] = loaded
        }
        return loaded
    }

    private suspend fun loadModel(speechModel: SpeechModel): Model? = withContext(Dispatchers.IO) {
        try {
            val destPath = if (speechModel.isBundled) {
                val path = File(context.filesDir, "vosk-model-${speechModel.id}").absolutePath
                val modelDir = File(path)
                if (!modelDir.exists()) {
                    Log.d(TAG, "Starting manual asset copy for Vosk model ${speechModel.id}...")
                    copyAssetDir(speechModel.assetPath ?: "model", modelDir)
                }
                path
            } else {
                File(File(context.filesDir, "speech-models"), speechModel.id).absolutePath
            }

            Log.d(TAG, "Initializing Vosk Model from $destPath...")
            Model(destPath)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load Vosk model ${speechModel.id}", e)
            null
        }
    }

    private fun copyAssetDir(assetDir: String, destDir: File) {
        val assetList = context.assets.list(assetDir) ?: return
        if (!destDir.exists()) destDir.mkdirs()

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
