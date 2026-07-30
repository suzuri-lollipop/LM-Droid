package com.suzuri.lmdroid.data.music

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/**
 * Plays music for the "play_music" tool (see ConversationRepository) via the standard
 * `ACTION_MEDIA_PLAY_FROM_SEARCH` intent — the same public, documented mechanism voice search
 * uses to hand a free-text query ("levitating by dua lipa", "my discover weekly playlist") off to
 * whichever music app (Spotify, YouTube Music, etc.) the user has chosen to handle it.
 */
class DeviceMusicController(private val context: Context) {

    /**
     * Null if no music app on this device registers for media-search playback; otherwise a
     * function that actually launches it — see the deferred-launch pattern shared with
     * DeviceAlarmController/DeviceNoteController/DeviceMessageController (ConversationRepository
     * queues the returned function rather than calling it immediately, so it doesn't interrupt the
     * screen before the reply describing it has been shown). When [preferredPackage] is set
     * (Settings → 音楽) and still installed, it's targeted directly; otherwise the system resolves
     * it itself — showing its own disambiguation dialog if more than one app can handle it and none
     * is set as default, the same as tapping a voice-search music result normally would.
     */
    fun preparePlayMusic(query: String, preferredPackage: String?): (() -> Unit)? {
        val apps = installedMusicApps()
        if (apps.isEmpty()) return null

        val searchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            // Without a focus hint, some apps (observed with YouTube Music) treat this as a plain
            // search and just show results instead of actually starting playback — "audio" is the
            // most general focus ("play something matching this"), matching what a simple free-text
            // query (no separate artist/album/playlist fields) can promise.
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
        }
        val targetPackage = preferredPackage?.takeIf { pkg -> apps.any { it.packageName == pkg } }

        return {
            val launchedPreferred = targetPackage != null &&
                launch(Intent(searchIntent).setPackage(targetPackage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "playMusic(preferred=$targetPackage)")
            if (!launchedPreferred) {
                launch(Intent(searchIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "playMusic(system-resolved)")
            }
        }
    }

    /**
     * Opens a specific YouTube Music track directly by video id (see [YouTubeDataApiClient]),
     * skipping YouTube Music's own search screen entirely — confirmed by hand to actually start
     * playback, unlike [preparePlayMusic]'s generic search intent, which YouTube Music only uses to
     * populate a search screen without playing anything. Null if YouTube Music isn't installed.
     */
    fun prepareOpenYoutubeMusicTrack(videoId: String): (() -> Unit)? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=$videoId")).apply {
            setPackage(YOUTUBE_MUSIC_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) return null
        return { launch(intent, "openYoutubeMusicTrack") }
    }

    /** Every installed app that can receive a media-search play request, sorted by display name — the candidates offered by the 音楽 screen's preferred-app picker. */
    fun installedMusicApps(): List<InstalledApp> {
        val probeIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
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
        Log.w(TAG, "$callSite: no music app available to handle $intent", e)
        false
    }

    data class InstalledApp(val packageName: String, val label: String)

    companion object {
        const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
        private const val TAG = "DeviceMusicController"
    }
}
