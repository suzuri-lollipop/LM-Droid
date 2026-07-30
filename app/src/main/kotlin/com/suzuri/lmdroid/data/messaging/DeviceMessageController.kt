package com.suzuri.lmdroid.data.messaging

import android.content.Context
import com.suzuri.lmdroid.data.share.TextShareLauncher

/**
 * Sends messages for the "send_message" tool (see ConversationRepository) by sharing text into a
 * messaging app (LINE, SMS, WhatsApp, or any other app that accepts a plain-text share) via
 * [TextShareLauncher]. The target app's own recipient picker (LINE's friend list, the SMS
 * composer's "To" field, etc.) is what actually addresses the message — there's no general way to
 * specify a recipient or send silently in the background from here.
 */
class DeviceMessageController(context: Context) {
    private val shareLauncher = TextShareLauncher(context)

    /** True if a messaging-capable app actually received the request — see [TextShareLauncher.send]. */
    fun sendMessage(content: String, preferredPackage: String?): Boolean =
        shareLauncher.send(content = content, subject = null, preferredPackage = preferredPackage)

    /** Every installed app that can receive the message — the candidates offered by the メッセージ screen's app picker. */
    fun installedMessagingApps(): List<TextShareLauncher.InstalledApp> = shareLauncher.installedApps()
}
