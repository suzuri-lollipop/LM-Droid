package com.suzuri.lmdroid.data.attachment

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A `content://` URI for a saved attachment file, grantable to other apps (share sheet, image
 * clipboard paste) via the app's FileProvider — the file itself lives in private storage, which
 * other apps can't read directly.
 */
fun attachmentContentUri(context: Context, filePath: String): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(filePath))

/**
 * Copies a saved attachment into the device's public Pictures gallery (via MediaStore) under a
 * "LM Droid" subfolder, so it survives the app being uninstalled and shows up in the user's
 * regular gallery app.
 */
suspend fun saveAttachmentToGallery(context: Context, filePath: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val fileName = "LMDroid_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LM Droid")
            } else {
                @Suppress("DEPRECATION")
                val picturesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "LM Droid")
                picturesDir.mkdirs()
                put(MediaStore.Images.Media.DATA, File(picturesDir, fileName).absolutePath)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create a gallery entry")
        val outputStream = context.contentResolver.openOutputStream(uri)
            ?: error("Unable to open the gallery entry for writing")
        outputStream.use { out -> File(filePath).inputStream().use { input -> input.copyTo(out) } }
        Unit
    }
}
