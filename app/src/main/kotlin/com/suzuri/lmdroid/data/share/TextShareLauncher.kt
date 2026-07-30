package com.suzuri.lmdroid.data.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Shares plain text into another installed app via the standard `ACTION_SEND` intent — the
 * mechanism behind both DeviceNoteController (メモ) and DeviceMessageController (メッセージ). No
 * such target app exposes a way to actually save/send silently in the background: this always
 * opens the target app's own compose screen (and, for a messaging app, its own recipient picker)
 * for the user to review and confirm there.
 */
class TextShareLauncher(private val context: Context) {

    /**
     * True if a share-capable app actually received the request. When [preferredPackage] is set
     * (and still installed), it's targeted directly; otherwise — or if launching it fails — the
     * system share chooser is shown so the user can pick an app themselves.
     */
    fun send(content: String, subject: String?, preferredPackage: String?): Boolean {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
            if (!subject.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
        }

        if (!preferredPackage.isNullOrBlank() && installedApps().any { it.packageName == preferredPackage }) {
            val direct = Intent(sendIntent).setPackage(preferredPackage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch(direct, "send(preferred=$preferredPackage)")) return true
        }

        val chooser = Intent.createChooser(sendIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(chooser, "send(chooser)")
    }

    /** Every installed app that can receive a plain-text share, sorted by display name — the candidates offered by a preferred-app picker (see NotesSettingsScreen, MessagingSettingsScreen). */
    fun installedApps(): List<InstalledApp> {
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
        Log.w(TAG, "$callSite: no app available to handle $intent", e)
        false
    }

    data class InstalledApp(val packageName: String, val label: String)

    private companion object {
        const val TAG = "TextShareLauncher"
    }
}
