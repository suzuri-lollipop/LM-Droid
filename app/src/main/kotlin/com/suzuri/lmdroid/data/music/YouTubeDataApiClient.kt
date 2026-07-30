package com.suzuri.lmdroid.data.music

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Thin client for the official YouTube Data API v3 search endpoint
 * (https://developers.google.com/youtube/v3/docs/search) — used only to resolve the "play_music"
 * tool's free-text query to a specific video id for
 * [DeviceMusicController.prepareOpenYoutubeMusicTrack]. YouTube Music's own
 * `ACTION_MEDIA_PLAY_FROM_SEARCH` handling only populates its search screen without actually
 * starting playback (confirmed by hand, and a widely-reported YouTube Music limitation — not
 * something fixable from the caller's side), so this is what makes "play X on YouTube Music"
 * actually play something instead of just opening a search. `videoCategoryId=10` (Music) narrows
 * results toward actual music content rather than an arbitrary video, though YouTube's own category
 * tagging isn't authoritative, so this is a best-effort filter, not a guarantee.
 */
class YouTubeDataApiClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    // Overridable only so tests can point this at a MockWebServer — the YouTube Data API has
    // exactly one real endpoint, unlike the OpenAI-compatible client, which callers self-host
    // anywhere.
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    /** The first matching video's id, or null if the search succeeded but found nothing. */
    suspend fun searchVideoId(apiKey: String, query: String): Result<String?> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/youtube/v3/search".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet")
            .addQueryParameter("type", "video")
            .addQueryParameter("videoCategoryId", MUSIC_CATEGORY_ID)
            .addQueryParameter("maxResults", "1")
            .addQueryParameter("q", query)
            .addQueryParameter("key", apiKey)
            .build()
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
                val videoId = bodyString
                    ?.let { runCatching { json.decodeFromString(YouTubeSearchResponse.serializer(), it) }.getOrNull() }
                    ?.items
                    ?.firstOrNull()
                    ?.id
                    ?.videoId
                Result.success(videoId)
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.googleapis.com"
        private const val TAG = "YouTubeDataApiClient"
        private const val MUSIC_CATEGORY_ID = "10"
    }
}

@Serializable
private data class YouTubeSearchResponse(val items: List<YouTubeSearchItem> = emptyList())

@Serializable
private data class YouTubeSearchItem(val id: YouTubeSearchItemId? = null)

@Serializable
private data class YouTubeSearchItemId(val videoId: String? = null)
