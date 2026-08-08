package com.suzuri.lmdroid.data.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A single saved attachment (image or audio) living in app-private storage — see [AttachmentFileStore]. */
data class SavedAttachment(
    val filePath: String,
    val mimeType: String,
)

/**
 * Persists picked attachment images (and, via [newAttachmentFile], recorded voice messages — see
 * AudioRecorder) into app-private storage rather than referencing the picker's content:// URI
 * directly — that URI's read grant isn't guaranteed to survive an app restart. Images are
 * downscaled and re-encoded as JPEG on the way in: an un-resized phone photo can be tens of
 * megabytes, which base64-inflates into a request body many vision-capable OpenAI-compatible
 * servers will reject or choke on.
 */
class AttachmentFileStore(context: Context) {
    private val appContext = context.applicationContext
    val attachmentsDir: File by lazy {
        File(appContext.filesDir, "attachments").apply { mkdirs() }
    }

    /** A fresh, uniquely-named file under [attachmentsDir] — shared by image saving and audio recording alike. */
    fun newAttachmentFile(extension: String): File = File(attachmentsDir, "${UUID.randomUUID()}.$extension")

    suspend fun save(uri: Uri): SavedAttachment = withContext(Dispatchers.IO) {
        // decodeStream with inJustDecodeBounds = true always returns null by contract (it only
        // fills outWidth/outHeight into the passed Options) — the open-failure check must be on
        // the stream itself, not the decode result, or this would (and did) throw on every call.
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = appContext.contentResolver.openInputStream(uri) ?: error("Unable to open $uri")
        boundsStream.use { input -> BitmapFactory.decodeStream(input, null, boundsOptions) }

        val sampleSize = sampleSizeFor(boundsOptions.outWidth, boundsOptions.outHeight, MAX_DIMENSION_PX)
        val decodeStream = appContext.contentResolver.openInputStream(uri) ?: error("Unable to open $uri")
        val sampledBitmap = decodeStream.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: error("Unable to decode $uri")

        val finalBitmap = downscaleIfNeeded(sampledBitmap, MAX_DIMENSION_PX)

        val file = newAttachmentFile("jpg")
        file.outputStream().use { out -> finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }

        if (finalBitmap !== sampledBitmap) sampledBitmap.recycle()
        finalBitmap.recycle()

        SavedAttachment(filePath = file.absolutePath, mimeType = "image/jpeg")
    }

    /** Saves raw bytes (e.g. from a network download) as a new attachment file. */
    suspend fun save(bytes: ByteArray, mimeType: String): SavedAttachment = withContext(Dispatchers.IO) {
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "audio/wav" -> "wav"
            "audio/mpeg" -> "mp3"
            else -> "jpg"
        }
        val file = newAttachmentFile(extension)
        file.writeBytes(bytes)
        SavedAttachment(filePath = file.absolutePath, mimeType = mimeType)
    }

    /** Saves a base64 data URI (e.g. "data:image/png;base64,...") as a new attachment file. */
    suspend fun saveBase64(dataUri: String): SavedAttachment = withContext(Dispatchers.IO) {
        val header = dataUri.substringBefore(',', "")
        val base64Data = dataUri.substringAfter(',', dataUri)
        val mimeType = if (header.startsWith("data:")) {
            header.removePrefix("data:").substringBefore(';')
        } else {
            "image/png" // Fallback
        }
        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
        save(bytes, mimeType)
    }

    /** Reads a saved attachment back out as a base64 data URI, for the vision request format. */
    suspend fun readAsDataUri(attachment: SavedAttachment): String = withContext(Dispatchers.IO) {
        "data:${attachment.mimeType};base64,${encodeBase64(attachment.filePath)}"
    }

    /** Raw base64 (no data-URI prefix) — the audio-input content part carries format separately. */
    suspend fun readAsBase64(filePath: String): String = withContext(Dispatchers.IO) {
        encodeBase64(filePath)
    }

    private fun encodeBase64(filePath: String): String =
        Base64.encodeToString(File(filePath).readBytes(), Base64.NO_WRAP)

    suspend fun delete(filePath: String) = withContext(Dispatchers.IO) {
        runCatching { File(filePath).delete() }
    }

    suspend fun deleteAll(filePaths: Collection<String>) = withContext(Dispatchers.IO) {
        filePaths.forEach { runCatching { File(it).delete() } }
    }

    private fun downscaleIfNeeded(bitmap: Bitmap, maxDimensionPx: Int): Bitmap {
        val largestSide = maxOf(bitmap.width, bitmap.height)
        if (largestSide <= maxDimensionPx) return bitmap
        val scale = maxDimensionPx.toFloat() / largestSide
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private companion object {
        // Matches common vision-model guidance (long side around ~1500px) — comfortably legible
        // while keeping the base64-encoded request body reasonably sized.
        const val MAX_DIMENSION_PX = 1568
        const val JPEG_QUALITY = 85
    }
}

/** Decodes a downsampled bitmap for a small UI preview, without loading the full-size image into memory. */
suspend fun decodeSampledBitmap(filePath: String, maxDimensionPx: Int): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, boundsOptions)
        val sampleSize = sampleSizeFor(boundsOptions.outWidth, boundsOptions.outHeight, maxDimensionPx)
        BitmapFactory.decodeFile(filePath, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    }.getOrNull()
}

private fun sampleSizeFor(width: Int, height: Int, maxDimensionPx: Int): Int {
    var sampleSize = 1
    while (width / (sampleSize * 2) >= maxDimensionPx || height / (sampleSize * 2) >= maxDimensionPx) {
        sampleSize *= 2
    }
    return sampleSize
}
