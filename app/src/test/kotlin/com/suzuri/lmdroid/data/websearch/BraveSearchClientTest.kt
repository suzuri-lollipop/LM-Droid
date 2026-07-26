package com.suzuri.lmdroid.data.websearch

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric so android.util.Log calls inside the client are shadowed rather than throwing.
@RunWith(RobolectricTestRunner::class)
class BraveSearchClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BraveSearchClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = BraveSearchClient(
            okHttpClient = OkHttpClient.Builder().build(),
            json = Json { ignoreUnknownKeys = true },
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search sends the query and subscription token header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"web\":{\"results\":[]}}"))

        client.search("test-key", "kyoto weather")

        val recordedRequest = server.takeRequest()
        assertTrue(recordedRequest.path?.contains("q=kyoto") == true)
        assertEquals("test-key", recordedRequest.getHeader("X-Subscription-Token"))
    }

    @Test
    fun `search parses title, url and description from web results`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "{\"web\":{\"results\":[" +
                    "{\"title\":\"Kyoto Weather\",\"url\":\"https://example.com/kyoto\",\"description\":\"Sunny today\"}" +
                    "]}}",
            ),
        )

        val result = client.search("test-key", "kyoto weather")

        assertTrue(result.isSuccess)
        val results = result.getOrNull().orEmpty()
        assertEquals(1, results.size)
        assertEquals("Kyoto Weather", results[0].title)
        assertEquals("https://example.com/kyoto", results[0].url)
        assertEquals("Sunny today", results[0].description)
    }

    @Test
    fun `search fails when the server returns a non-2xx response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

        val result = client.search("bad-key", "kyoto weather")

        assertTrue(result.isFailure)
    }

    @Test
    fun `search returns an empty list rather than failing when there are no web results`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = client.search("test-key", "an obscure query")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull().orEmpty().isEmpty())
    }
}
