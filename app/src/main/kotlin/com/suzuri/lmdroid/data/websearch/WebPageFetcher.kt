package com.suzuri.lmdroid.data.websearch

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches a web page and extracts its visible text — used by the "fetch_webpage" tool (see
 * ConversationRepository) so the model can read past a [BraveSearchClient] result's snippet when
 * it needs more detail. This is a plain HTTP GET followed by static HTML parsing: it cannot
 * execute JavaScript, so content a page only renders client-side after load (common on sites that
 * fill in data like current weather via a script) won't be captured — only what's present in the
 * server-rendered HTML.
 */
class WebPageFetcher(okHttpClient: OkHttpClient) {
    // A much tighter timeout than the shared client's (tuned for slow local LLM SSE streams) —
    // an arbitrary third-party page the model asked to fetch shouldn't be able to stall the whole
    // tool-calling turn for minutes.
    private val client = okHttpClient.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetchTextContent(url: String): Result<String> = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        } catch (e: IllegalArgumentException) {
            return@withContext Result.failure(e)
        }

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Fetching $url failed: HTTP ${response.code}")
                    return@withContext Result.failure(IOException("Fetching the page failed: HTTP ${response.code}"))
                }
                // Passing the raw stream (charset = null) lets Jsoup sniff the actual encoding from
                // a BOM or the HTML's own <meta charset>/http-equiv tag, rather than trusting
                // OkHttp's header-only guess — plenty of real-world (including Japanese) sites
                // declare their charset only in the markup, not the HTTP Content-Type header.
                val document = Jsoup.parse(response.body.byteStream(), null, url)
                document.select("script, style, noscript").remove()
                val text = document.body().text()
                val content = if (text.length > MAX_CONTENT_LENGTH) {
                    text.take(MAX_CONTENT_LENGTH) + "\n\n…（以降は省略されました）"
                } else {
                    text
                }
                Result.success(content)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Fetching $url failed", e)
            Result.failure(e)
        }
    }

    private companion object {
        const val TAG = "WebPageFetcher"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) LMDroid/1.0"
        const val MAX_CONTENT_LENGTH = 6000
    }
}
