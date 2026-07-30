package com.suzuri.lmdroid.data.notes

import android.content.Context
import com.suzuri.lmdroid.data.share.TextShareLauncher

/**
 * Creates memos for the "create_note" tool (see ConversationRepository) by sharing text into a
 * note-taking app (Google Keep, or any other app that accepts a plain-text share) via
 * [TextShareLauncher].
 */
class DeviceNoteController(context: Context) {
    private val shareLauncher = TextShareLauncher(context)

    /** True if a note-capable app actually received the request — see [TextShareLauncher.send]. */
    fun createNote(title: String?, content: String, preferredPackage: String?): Boolean =
        shareLauncher.send(content = content, subject = title, preferredPackage = preferredPackage)

    /** Every installed app that can receive the note — the candidates offered by the メモ screen's app picker. */
    fun installedNoteApps(): List<TextShareLauncher.InstalledApp> = shareLauncher.installedApps()
}
