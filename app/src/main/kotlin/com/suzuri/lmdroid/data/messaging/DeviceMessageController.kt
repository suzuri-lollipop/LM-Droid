package com.suzuri.lmdroid.data.messaging

import android.content.Context
import com.suzuri.lmdroid.data.share.TextShareLauncher

/**
 * Sends messages for the "send_message" tool (see ConversationRepository) by sharing text into a
 * messaging app (LINE, SMS, WhatsApp, or any other app that accepts a plain-text share) via
 * [TextShareLauncher]. The target app's own recipient picker (LINE's friend list, the SMS
 * composer's "To" field, etc.) is what actually addresses the message — there's no general way to
 * specify a recipient or send silently in the background from here.
 *
 * A contact-based "open LINE directly in a named person's chat" path was prototyped (via the
 * Data row LINE writes into the Contacts Provider when its own "友だちを連絡先に追加" setting is
 * on) but abandoned: firing the same ACTION_VIEW intent the Contacts app uses resolves to LINE's
 * `ContactLauncherActivity` either way, but LINE's app only completes the hand-off into the actual
 * chat when the Contacts app itself is the caller — from this app it fails with a generic LINE
 * error. Reproducing whatever extra data the Contacts app attaches isn't publicly documented and
 * would mean reverse-engineering Google's Contacts app, so the plain share flow below is the only
 * supported path for now.
 */
class DeviceMessageController(context: Context) {
    private val shareLauncher = TextShareLauncher(context)

    /** Null if no messaging-capable app exists on this device; otherwise a function that actually opens it — see [TextShareLauncher.prepareSend]. */
    fun prepareSendMessage(content: String, preferredPackage: String?): (() -> Unit)? =
        shareLauncher.prepareSend(content = content, subject = null, preferredPackage = preferredPackage)

    /** Every installed app that can receive the message — the candidates offered by the メッセージ screen's app picker. */
    fun installedMessagingApps(): List<TextShareLauncher.InstalledApp> = shareLauncher.installedApps()
}
