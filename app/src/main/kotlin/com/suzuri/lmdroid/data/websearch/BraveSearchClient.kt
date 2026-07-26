package com.suzuri.lmdroid.data.websearch

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class WebSearchResult(
    val title: String,
    val url: String,
    val description: String,
)

/**
 * Thin client for the Brave Web Search API (https://api.search.brave.com). When enabled in
 * Settings, the model is offered this as a "web_search" tool it can call on its own (agentic
 * tool-calling) — [ConversationRepository][com.suzuri.lmdroid.data.repository.ConversationRepository]'s
 * reply loop invokes [search] only when the model actually requests it, never unconditionally.
 */
class BraveSearchClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    // Overridable only so tests can point this at a MockWebServer — Brave Search has exactly one
    // real endpoint, unlike the OpenAI-compatible client, which callers self-host anywhere.
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    suspend fun search(apiKey: String, query: String, count: Int = DEFAULT_RESULT_COUNT): Result<List<WebSearchResult>> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/res/v1/web/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("count", count.coerceIn(1, 20).toString())
                .build()
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("X-Subscription-Token", apiKey)
                .get()
                .build()

            try {
                okHttpClient.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Brave Search API returned ${response.code}: ${bodyString?.take(300)}")
                        return@withContext Result.failure(
                            IOException("Brave Search API returned HTTP ${response.code}"),
                        )
                    }
                    val results = bodyString
                        ?.let { runCatching { json.decodeFromString(BraveSearchResponse.serializer(), it) }.getOrNull() }
                        ?.web
                        ?.results
                        .orEmpty()
                        .map { WebSearchResult(title = it.title, url = it.url, description = it.description) }
                    Result.success(results)
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.search.brave.com"
        private const val TAG = "BraveSearchClient"
        private const val DEFAULT_RESULT_COUNT = 5
    }
}

@Serializable
private data class BraveSearchResponse(val web: BraveWebResults? = null)

@Serializable
private data class BraveWebResults(val results: List<BraveResult> = emptyList())

@Serializable
private data class BraveResult(
    val title: String = "",
    val url: String = "",
    val description: String = "",
)
