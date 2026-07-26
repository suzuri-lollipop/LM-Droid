package com.suzuri.lmdroid.data.websearch

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric so android.util.Log calls inside the fetcher are shadowed rather than throwing.
@RunWith(RobolectricTestRunner::class)
class WebPageFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: WebPageFetcher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fetcher = WebPageFetcher(OkHttpClient.Builder().build())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetchTextContent strips script and style tags from the extracted text`() = runTest {
        val html = "<html><head><style>body{color:red}</style></head>" +
            "<body><script>var x=1;</script><p>Hello world</p></body></html>"
        server.enqueue(MockResponse().setResponseCode(200).setBody(html))

        val result = fetcher.fetchTextContent(server.url("/page").toString())

        assertTrue(result.isSuccess)
        val text = result.getOrNull().orEmpty()
        assertTrue(text.contains("Hello world"))
        assertFalse(text.contains("color:red"))
        assertFalse(text.contains("var x"))
    }

    @Test
    fun `fetchTextContent decodes Shift_JIS from a meta tag when the HTTP header declares no charset`() = runTest {
        // Regression test: reading the response via OkHttp's ResponseBody.string() would decode
        // using the HTTP header's charset (or UTF-8 if absent) — but plenty of real-world
        // (especially older Japanese) sites declare their actual charset only in the HTML markup.
        // Passing the raw byte stream straight to Jsoup lets it sniff the true encoding instead.
        val html = "<html><head><meta charset=\"Shift_JIS\"></head><body><p>こんにちは世界</p></body></html>"
        val shiftJisBytes = html.toByteArray(charset("Shift_JIS"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody(Buffer().write(shiftJisBytes)),
        )

        val result = fetcher.fetchTextContent(server.url("/page").toString())

        assertTrue(result.isSuccess)
        assertEquals("こんにちは世界", result.getOrNull())
    }

    @Test
    fun `fetchTextContent fails on a non-2xx response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))

        val result = fetcher.fetchTextContent(server.url("/missing").toString())

        assertTrue(result.isFailure)
    }

    @Test
    fun `fetchTextContent truncates very long content`() = runTest {
        val longText = "a".repeat(10_000)
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body><p>$longText</p></body></html>"))

        val result = fetcher.fetchTextContent(server.url("/page").toString())

        assertTrue(result.isSuccess)
        val text = result.getOrNull().orEmpty()
        assertTrue(text.length < 10_000)
        assertTrue(text.contains("省略"))
    }
}
