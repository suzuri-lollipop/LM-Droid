package com.suzuri.lmdroid.data.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class SpeechModelManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val modelsDir = File(context.filesDir, "speech-models")

    init {
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
    }

    fun isModelAvailable(model: SpeechModel): Boolean {
        if (model.isBundled) return true
        val dest = getModelDir(model)
        return when (model.engineType) {
            SpeechEngineType.VOSK -> dest.exists() && File(dest, "am").exists()
            SpeechEngineType.WHISPER -> dest.exists() && dest.isFile
        }
    }

    fun getModelDir(model: SpeechModel): File {
        return File(modelsDir, model.id)
    }

    suspend fun downloadModel(
        model: SpeechModel,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val url = model.url ?: return@withContext Result.failure(Exception("Model has no download URL"))
        val dest = getModelDir(model)
        
        try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed to download model: ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty response body"))
            val contentLength = body.contentLength()
            val tempFile = File(modelsDir, "${model.id}.tmp")

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalRead += read
                        if (contentLength > 0) {
                            onProgress(totalRead.toFloat() / contentLength)
                        }
                    }
                }
            }

            if (model.engineType == SpeechEngineType.VOSK) {
                // Extract zip
                dest.mkdirs()
                extractZip(tempFile, dest)
                tempFile.delete()
                
                // Vosk models often have a nested directory inside the zip.
                // We should move everything up if necessary.
                val subDirs = dest.listFiles { f -> f.isDirectory }
                if (subDirs?.size == 1 && File(subDirs[0], "am").exists()) {
                    val actualModelDir = subDirs[0]
                    actualModelDir.listFiles()?.forEach { f ->
                        f.renameTo(File(dest, f.name))
                    }
                    actualModelDir.delete()
                }
            } else {
                // Whisper: just rename
                tempFile.renameTo(dest)
            }

            Result.success(dest)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model ${model.id}", e)
            Result.failure(e)
        }
    }

    private fun extractZip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
    
    fun deleteModel(model: SpeechModel) {
        val dest = getModelDir(model)
        if (dest.exists()) {
            dest.deleteRecursively()
        }
    }

    companion object {
        private const val TAG = "SpeechModelManager"
    }
}
