package com.suzuri.lmdroid.data.network

import app.cash.turbine.test
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

class OpenAiApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenAiApiClient(
            okHttpClient = OkHttpClient.Builder().build(),
            json = Json { ignoreUnknownKeys = true },
            baseUrl = server.url("/v1").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `streamChatCompletion emits deltas then done`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )

        client.streamChatCompletion("test-key", "gpt-4o-mini", listOf(ChatMessageDto("user", "hi"))).test {
            assertEquals(StreamEvent.Delta("Hel"), awaitItem())
            assertEquals(StreamEvent.Delta("lo"), awaitItem())
            assertEquals(StreamEvent.Done, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `streamChatCompletion ignores malformed chunk and continues`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: not-json\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )

        client.streamChatCompletion("test-key", "gpt-4o-mini", listOf(ChatMessageDto("user", "hi"))).test {
            assertEquals(StreamEvent.Delta("ok"), awaitItem())
            assertEquals(StreamEvent.Done, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `streamChatCompletion maps 401 to InvalidApiKey`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"invalid\"}}"),
        )

        client.streamChatCompletion("bad-key", "gpt-4o-mini", listOf(ChatMessageDto("user", "hi"))).test {
            val error = awaitError()
            assertTrue(error is OpenAiException.InvalidApiKey)
        }
    }

    @Test
    fun `testApiKey succeeds on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = client.testApiKey("test-key")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `testApiKey maps 429 to RateLimited`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody("{\"error\":{\"message\":\"rate limited\"}}"),
        )

        val result = client.testApiKey("test-key")

        assertTrue(result.exceptionOrNull() is OpenAiException.RateLimited)
    }

    @Test
    fun `testApiKey maps 500 to ServerError`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))

        val result = client.testApiKey("test-key")

        assertTrue(result.exceptionOrNull() is OpenAiException.ServerError)
    }
}
