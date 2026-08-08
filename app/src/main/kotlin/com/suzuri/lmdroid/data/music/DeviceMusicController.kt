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
    fun preparePlayMusic(query: String, preferredPackage: String?, focus: String = FOCUS_AUDIO): (() -> Unit)? {
        val apps = installedMusicApps()
        if (apps.isEmpty()) return null

        val searchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            // Without a focus hint, some apps (observed with YouTube Music) treat this as a plain
            // search and just show results instead of actually starting playback. [focus] lets a
            // caller that already knows it's an album/playlist request say so (e.g. for apps like
            // Spotify that reportedly honor this), defaulting to the most general "play something
            // matching this" for a plain query.
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, focus)
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

    /**
     * Opens a YouTube Music album/playlist directly by playlist id (see
     * [YouTubeDataApiClient.searchPlaylistId] — an "album" page is backed by a playlist id the same
     * as an ordinary playlist). Null if YouTube Music isn't installed. [firstVideoId] (see
     * [YouTubeDataApiClient.getFirstPlaylistItemVideoId]), when known, is combined into a
     * "watch this video, then continue through this playlist" URL — the same shape YouTube/YouTube
     * Music itself uses when you tap a track from inside a playlist — since a bare playlist URL on
     * its own only opens the playlist's browsing page without starting playback.
     */
    fun prepareOpenYoutubeMusicPlaylist(playlistId: String, firstVideoId: String? = null): (() -> Unit)? {
        val uri = if (firstVideoId != null) {
            "https://music.youtube.com/watch?v=$firstVideoId&list=$playlistId"
        } else {
            "https://music.youtube.com/playlist?list=$playlistId"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(YOUTUBE_MUSIC_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) return null
        return { launch(intent, "openYoutubeMusicPlaylist") }
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

        // MediaStore.EXTRA_MEDIA_FOCUS values — see
        // https://developer.android.com/reference/android/provider/MediaStore#EXTRA_MEDIA_FOCUS
        const val FOCUS_AUDIO = "vnd.android.cursor.item/audio"
        const val FOCUS_ALBUM = "vnd.android.cursor.item/album"
        const val FOCUS_PLAYLIST = "vnd.android.cursor.item/playlist"

        private const val TAG = "DeviceMusicController"
    }
}
