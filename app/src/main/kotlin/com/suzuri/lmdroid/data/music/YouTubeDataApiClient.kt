package com.suzuri.lmdroid.data.music

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Thin client for the official YouTube Data API v3 (https://developers.google.com/youtube/v3/docs)
 * — used only to resolve the "play_music" tool's free-text query to a specific id for
 * [DeviceMusicController.prepareOpenYoutubeMusicTrack] (a single song) or
 * [DeviceMusicController.prepareOpenYoutubeMusicPlaylist] (a whole album/playlist — YouTube Music's
 * "album" pages are backed by a playlist id, same as an ordinary playlist). YouTube Music's own
 * `ACTION_MEDIA_PLAY_FROM_SEARCH` handling only populates its search screen without actually
 * starting playback (confirmed by hand, and a widely-reported YouTube Music limitation — not
 * something fixable from the caller's side), so this is what makes "play X on YouTube Music"
 * actually play something instead of just opening a search.
 */
class YouTubeDataApiClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    // Overridable only so tests can point this at a MockWebServer — the YouTube Data API has
    // exactly one real endpoint, unlike the OpenAI-compatible client, which callers self-host
    // anywhere.
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    /** The first matching video's id, or null if the search succeeded but found nothing. `videoCategoryId=10` (Music) narrows results toward actual music content rather than an arbitrary video, though YouTube's own category tagging isn't authoritative, so this is a best-effort filter, not a guarantee. */
    suspend fun searchVideoId(apiKey: String, query: String): Result<String?> =
        searchItems(apiKey, query, type = "video", maxResults = 1, videoCategoryId = MUSIC_CATEGORY_ID)
            .map { items -> items.firstOrNull()?.id?.videoId }

    /**
     * A matching playlist's id (also what backs a YouTube Music "album" page), or null if the
     * search succeeded but found nothing. `videoCategoryId` isn't a valid parameter for a playlist
     * search, and the API has no "official album" flag to filter on — unlike a video search, a
     * plain top result here is just as likely to be a fan-made compilation as the real album. To
     * bias toward the real thing, this fetches a few candidates and prefers whichever one's
     * uploading channel name shows up in [query] (an artist's own official album uploads are
     * normally on a channel named after the artist; a fan compilation usually isn't) — a heuristic,
     * not a guarantee, since nothing in the official API actually distinguishes them.
     */
    suspend fun searchPlaylistId(apiKey: String, query: String): Result<String?> =
        searchItems(apiKey, query, type = "playlist", maxResults = PLAYLIST_CANDIDATE_COUNT, videoCategoryId = null)
            .map { items -> pickBestPlaylistMatch(items, query)?.id?.playlistId }

    /** The video id of a playlist/album's first track, or null if it has none — combined with the playlist's own id into a "watch, then continue through this playlist" URL, since opening a playlist's own page on its own does not start playback (see [DeviceMusicController.prepareOpenYoutubeMusicPlaylist]). */
    suspend fun getFirstPlaylistItemVideoId(apiKey: String, playlistId: String): Result<String?> {
        val url = "$baseUrl/youtube/v3/playlistItems".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet")
            .addQueryParameter("playlistId", playlistId)
            .addQueryParameter("maxResults", "1")
            .addQueryParameter("key", apiKey)
            .build()
        return getJson(url).map { body ->
            runCatching { json.decodeFromString(YouTubePlaylistItemsResponse.serializer(), body) }.getOrNull()
                ?.items
                ?.firstOrNull()
                ?.snippet
                ?.resourceId
                ?.videoId
        }
    }

    private fun pickBestPlaylistMatch(items: List<YouTubeSearchItem>, query: String): YouTubeSearchItem? {
        val queryLower = query.lowercase()
        return items.firstOrNull { item ->
            val channelTitle = item.snippet?.channelTitle?.lowercase()
            !channelTitle.isNullOrBlank() && queryLower.contains(channelTitle)
        } ?: items.firstOrNull()
    }

    private suspend fun searchItems(
        apiKey: String,
        query: String,
        type: String,
        maxResults: Int,
        videoCategoryId: String?,
    ): Result<List<YouTubeSearchItem>> {
        val urlBuilder = "$baseUrl/youtube/v3/search".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet")
            .addQueryParameter("type", type)
            .addQueryParameter("maxResults", maxResults.toString())
            .addQueryParameter("q", query)
            .addQueryParameter("key", apiKey)
        if (videoCategoryId != null) urlBuilder.addQueryParameter("videoCategoryId", videoCategoryId)
        return getJson(urlBuilder.build()).map { body ->
            runCatching { json.decodeFromString(YouTubeSearchResponse.serializer(), body) }.getOrNull()?.items.orEmpty()
        }
    }

    /** Runs a GET request and returns the raw response body — shared by every endpoint above, which each decode it into their own response shape. */
    private suspend fun getJson(url: HttpUrl): Result<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .get()
            .build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string()
                if (!response.isSuccessful) {
                    Log.w(TAG, "YouTube Data API returned ${response.code}: ${bodyString?.take(300)}")
                    return@withContext Result.failure(IOException("YouTube Data API returned HTTP ${response.code}"))
                }
                Result.success(bodyString.orEmpty())
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.googleapis.com"
        private const val TAG = "YouTubeDataApiClient"
        private const val MUSIC_CATEGORY_ID = "10"
        private const val PLAYLIST_CANDIDATE_COUNT = 5
    }
}

@Serializable
private data class YouTubeSearchResponse(val items: List<YouTubeSearchItem> = emptyList())

@Serializable
private data class YouTubeSearchItem(val id: YouTubeSearchItemId? = null, val snippet: YouTubeSearchSnippet? = null)

// Only one of these is ever populated, depending on the search's "type" — video searches return
// videoId, playlist searches return playlistId.
@Serializable
private data class YouTubeSearchItemId(val videoId: String? = null, val playlistId: String? = null)

@Serializable
private data class YouTubeSearchSnippet(val channelTitle: String? = null)

@Serializable
private data class YouTubePlaylistItemsResponse(val items: List<YouTubePlaylistItem> = emptyList())

@Serializable
private data class YouTubePlaylistItem(val snippet: YouTubePlaylistItemSnippet? = null)

@Serializable
private data class YouTubePlaylistItemSnippet(val resourceId: YouTubeResourceId? = null)

@Serializable
private data class YouTubeResourceId(val videoId: String? = null)
