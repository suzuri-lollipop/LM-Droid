package com.suzuri.lmdroid.data.notes

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Creates memos for the "create_note" tool (see ConversationRepository) by sharing text into a
 * note-taking app (Google Keep, or any other app that accepts a plain-text share — see
 * [installedNoteApps]) via the standard `ACTION_SEND` intent.
 *
 * Unlike DeviceAlarmController's `EXTRA_SKIP_UI`, no note app exposes a way to save a note silently
 * in the background — this always opens the target app's own compose/share screen for the user to
 * review and save.
 */
class DeviceNoteController(private val context: Context) {

    /**
     * True if a note-capable app actually received the request. When [preferredPackage] is set
     * (Settings → メモ) and still installed, it's targeted directly; otherwise (or if launching it
     * fails) the system share chooser is shown so the user can pick an app themselves.
     */
    fun createNote(title: String?, content: String, preferredPackage: String?): Boolean {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
            if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
        }

        if (!preferredPackage.isNullOrBlank() && installedNoteApps().any { it.packageName == preferredPackage }) {
            val direct = Intent(sendIntent).setPackage(preferredPackage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch(direct, "createNote(preferred=$preferredPackage)")) return true
        }

        val chooser = Intent.createChooser(sendIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(chooser, "createNote(chooser)")
    }

    /** Every installed app that can receive a plain-text share, sorted by display name — the candidates offered by the メモ screen's app picker. */
    fun installedNoteApps(): List<InstalledApp> {
        val probeIntent = Intent(Intent.ACTION_SEND).setType("text/plain")
        val packageManager = context.packageManager
        return packageManager.queryIntentActivities(probeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { resolveInfo ->
                InstalledApp(
                    packageName = resolveInfo.activityInfo.packageName,
                    label = resolveInfo.loadLabel(packageManager).toString(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
    }

    private fun launch(intent: Intent, callSite: String): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "$callSite: no note app available to handle $intent", e)
        false
    }

    data class InstalledApp(val packageName: String, val label: String)

    private companion object {
        const val TAG = "DeviceNoteController"
    }
}
