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
     * Builds the share without launching anything yet — callers (the tool-calling round trip in
     * ConversationRepository) use this to confirm a share is possible and describe it to the model
     * before actually showing any UI, then invoke the returned function once the reply that
     * describes the action has been fully shown, so the share chooser doesn't interrupt the screen
     * before the user has seen/heard why. Returns null only when no app at all can receive a
     * plain-text share on this device. The returned function tries [preferredPackage] first (if
     * still installed), falling back to the system share chooser if that specific launch fails.
     */
    fun prepareSend(content: String, subject: String?, preferredPackage: String?): (() -> Unit)? {
        val apps = installedApps()
        if (apps.isEmpty()) return null

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
            if (!subject.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        val targetPackage = preferredPackage?.takeIf { pkg -> apps.any { it.packageName == pkg } }

        return {
            val launchedPreferred = targetPackage != null &&
                launch(Intent(sendIntent).setPackage(targetPackage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "send(preferred=$targetPackage)")
            if (!launchedPreferred) {
                launch(Intent.createChooser(sendIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "send(chooser)")
            }
        }
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
