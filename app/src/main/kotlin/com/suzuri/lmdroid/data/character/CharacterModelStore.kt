package com.suzuri.lmdroid.data.character

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies character assets picked via SAF (standing-sprite image, background image — later
 * Live2D/MMD model bundles) into internal storage, since a content:// uri's read permission can
 * lapse while an internal copy stays readable for the app's lifetime. Each asset kind is a named
 * "slot": re-importing replaces the slot's previous file (whatever its extension was), matching
 * how SettingsRepository stores only the resulting absolute path.
 */
class CharacterModelStore(private val context: Context) {

    private val charactersDir: File
        get() = File(context.filesDir, "characters").apply { mkdirs() }

    /** Copies [uri]'s contents into [slot] (e.g. [SLOT_SPRITE]) and returns the stored file's absolute path, or null when the file couldn't be read. */
    suspend fun importImage(uri: Uri, slot: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val extension = context.contentResolver.getType(uri)
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?: "png"
            val target = File(charactersDir, "$slot.$extension")
            clearSlotFiles(slot, except = target)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("contentResolver.openInputStream returned null for $uri")
            target.absolutePath
        }.getOrNull()
    }

    /** Removes whatever asset currently occupies [slot] (the settings path is cleared by the caller — see CharacterSettingsViewModel). */
    suspend fun clear(slot: String) = withContext(Dispatchers.IO) {
        clearSlotFiles(slot, except = null)
    }

    private fun clearSlotFiles(slot: String, except: File?) {
        charactersDir.listFiles { file -> file.name.startsWith("$slot.") }
            ?.forEach { file -> if (file != except) file.delete() }
    }

    companion object {
        const val SLOT_SPRITE = "sprite"
        const val SLOT_BACKGROUND = "background"
    }
}
