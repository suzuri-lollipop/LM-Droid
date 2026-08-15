package com.suzuri.lmdroid.data.character

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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

    /**
     * Imports an MMD model from a SAF directory tree ([treeUri] from OpenDocumentTree): finds
     * the first .pmx, then copies it plus every texture file reachable in the tree (preserving
     * relative sub-paths, since PMX texture entries look like "tex/face.png") into
     * `characters/mmd/<slug>/`. Returns the stored .pmx's absolute path, or null when the tree
     * contains no .pmx. The previous MMD model is replaced.
     */
    suspend fun importMmdModel(treeUri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val entries = listTree(treeUri)
            val pmxEntry = entries.firstOrNull { it.name.endsWith(".pmx", ignoreCase = true) }
                ?: return@runCatching null
            val targetDir = File(charactersDir, "mmd/${slugify(pmxEntry.name)}")
            File(charactersDir, "mmd").deleteRecursively()
            targetDir.mkdirs()

            var pmxPath: String? = null
            for (entry in entries) {
                val isAsset = entry.name.endsWith(".pmx", ignoreCase = true) ||
                    entry.name.lowercase().let { name -> TEXTURE_EXTENSIONS.any { name.endsWith(it) } }
                if (!isAsset) continue
                val target = File(targetDir, entry.relativePath.replace('\\', '/'))
                target.parentFile?.mkdirs()
                copyDocument(entry.uri, target)
                if (entry.name.equals(pmxEntry.name, ignoreCase = true)) pmxPath = target.absolutePath
            }
            pmxPath
        }.getOrNull()
    }

    /**
     * File-first MMD import: the user picks the .pmx itself (a plain file picker), then this
     * copies the .pmx and best-effort recovers the sibling texture files by reconstructing the
     * containing folder's tree uri. That reconstruction only works for the platform's own
     * external-storage provider (document ids are "volume:path"), so third-party providers
     * degrade to importing the .pmx alone — the folder picker ([importMmdModel]) stays available
     * as the reliable path for those.
     */
    suspend fun importMmdModelFromFile(pmxUri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = queryDisplayName(pmxUri) ?: "model.pmx"
            if (!displayName.endsWith(".pmx", ignoreCase = true)) return@runCatching null
            val slug = slugify(displayName)
            val targetDir = File(charactersDir, "mmd/$slug")
            File(charactersDir, "mmd").deleteRecursively()
            targetDir.mkdirs()
            val pmxTarget = File(targetDir, displayName)
            copyDocument(pmxUri, pmxTarget)

            // Best-effort: pull the textures that live next to the .pmx.
            parentTreeUriFor(pmxUri)?.let { treeUri ->
                runCatching {
                    // A tree uri we constructed ourselves was never granted by a picker intent,
                    // so claim a persistable read grant before walking it (throws when the
                    // provider refuses — handled by the outer runCatching).
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                runCatching {
                    for (entry in listTree(treeUri)) {
                        val isTexture = entry.name.lowercase().let { name ->
                            TEXTURE_EXTENSIONS.any { name.endsWith(it) }
                        }
                        if (!isTexture) continue
                        val target = File(targetDir, entry.relativePath.replace('\\', '/'))
                        target.parentFile?.mkdirs()
                        runCatching { copyDocument(entry.uri, target) }
                    }
                }
            }
            pmxTarget.absolutePath
        }.getOrNull()
    }

    /** Rebuilds the containing folder's tree uri for a picked document, when the provider exposes the standard "volume:path" id scheme (local storage only). */
    private fun parentTreeUriFor(documentUri: Uri): Uri? {
        val authority = documentUri.authority ?: return null
        if (authority != "com.android.externalstorage.documents") return null
        return runCatching {
            val documentId = DocumentsContract.getDocumentId(documentUri)
            val separator = documentId.lastIndexOf(':')
            if (separator < 0) return null
            val volume = documentId.substring(0, separator)
            val path = documentId.substring(separator + 1)
            val parentPath = path.substringBeforeLast('/', missingDelimiterValue = "")
            DocumentsContract.buildTreeDocumentUri(authority, "$volume:$parentPath")
        }.getOrNull()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            val column = cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) cursor.getString(column) else null
        }
    }

    private fun slugify(fileName: String): String {
        return fileName.removeSuffix(".pmx").removeSuffix(".PMX")
            .replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
            .ifBlank { "model" }
    }

    /** Copies a picked .vmd into the single motion slot, replacing whatever was there before. */
    suspend fun importMotion(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(charactersDir, "$SLOT_MOTION.vmd")
            clearSlotFiles(SLOT_MOTION, except = target)
            copyDocument(uri, target)
            target.absolutePath
        }.getOrNull()
    }

    /** Removes the stored MMD model directory and the motion slot (settings paths cleared by the caller). */
    suspend fun clearMmdModel() = withContext(Dispatchers.IO) {
        File(charactersDir, "mmd").deleteRecursively()
        clearSlotFiles(SLOT_MOTION, except = null)
    }

    private fun copyDocument(uri: Uri, target: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("contentResolver.openInputStream returned null for $uri")
    }

    private data class TreeEntry(val uri: Uri, val name: String, val relativePath: String, val isDirectory: Boolean)

    /** Flattens a SAF document tree into readable entries, recursing into sub-directories. */
    private fun listTree(treeUri: Uri): List<TreeEntry> {
        val results = mutableListOf<TreeEntry>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        walk(treeUri, rootId, "", results, depth = 0)
        return results
    }

    private fun walk(treeUri: Uri, documentId: String, relativeRoot: String, results: MutableList<TreeEntry>, depth: Int) {
        // SAF trees from hostile pickers can be arbitrarily deep; PMX texture folders never are.
        if (depth > 8) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val childId = cursor.getString(idColumn) ?: continue
                val name = cursor.getString(nameColumn) ?: continue
                val mime = cursor.getString(mimeColumn) ?: continue
                val relativePath = if (relativeRoot.isEmpty()) name else "$relativeRoot/$name"
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(treeUri, childId, relativePath, results, depth + 1)
                } else {
                    results.add(TreeEntry(childUri, name, relativePath, isDirectory = false))
                }
            }
        }
    }

    companion object {
        const val SLOT_SPRITE = "sprite"
        const val SLOT_BACKGROUND = "background"
        const val SLOT_MOTION = "motion"
        private val TEXTURE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".bmp", ".tga", ".spa", ".sph")
    }
}
