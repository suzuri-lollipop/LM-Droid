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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric (rather than a plain JVM JUnit run) is needed so android.util.Log calls made by
// OpenAiApiClient (e.g. logging a malformed SSE chunk) are shadowed as no-ops instead of throwing
// "not mocked".
@RunWith(RobolectricTestRunner::class)
class OpenAiApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiApiClient
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenAiApiClient(
            okHttpClient = OkHttpClient.Builder().build(),
            // Must mirror AppContainer's Json config exactly: encodeDefaults = true is what
            // keeps "stream" and "max_tokens" in the outgoing JSON (see the regression test
            // below) — kotlinx.serialization silently drops fields that equal their Kotlin
            // default otherwise, which is what disabled server-side streaming in production.
            json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
        )
        baseUrl = server.url("/v1").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `streamChatCompletion sends stream=true in the request body`() = runTest {
        // Regression test: "stream" defaults to true and was therefore being silently omitted
        // from the JSON (kotlinx.serialization's encodeDefaults defaults to false), so the
        // server never saw it, fell back to non-streaming, and buffered the whole response.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )

        client.streamChatCompletion("test-key", "gpt-4o-mini", listOf(chatMessage("user", "hi")), baseUrl).test {
            awaitItem() // Done
            awaitComplete()
        }

        val recordedRequest = server.takeRequest()
        assertTrue(recordedRequest.body.readUtf8().contains("\"stream\":true"))
    }

    @Test
    fun `streamChatCompletion serializes a multimodal message as a content parts array`() = runTest {
        // Regression test for MessageContentSerializer: a text-only message's "content" must stay
        // a plain JSON string (unchanged wire format), but a message with an image attachment
        // must switch to the OpenAI vision request shape — an array of typed parts, each tagged
        // with a bare "type" discriminator ("text"/"image_url"), not a wrapped/qualified one.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )

        val message = ChatMessageDto(
            role = "user",
            content = MessageContent.Parts(
                listOf(
                    ContentPart.TextPart("what is this?"),
                    ContentPart.ImagePart(ImageUrl("data:image/jpeg;base64,AAAA")),
                ),
            ),
        )

        client.streamChatCompletion("test-key", "gpt-4o-mini", listOf(message), baseUrl).test {
            awaitItem() // Done
            awaitComplete()
        }

        val requestBody = server.takeRequest().body.readUtf8()
        assertTrue(requestBody.contains("\"type\":\"text\""))
        assertTrue(requestBody.contains("\"type\":\"image_url\""))
        assertTrue(requestBody.contains("what is this?"))
        assertTrue(requestBody.contains("data:image/jpeg;base64,AAAA"))
    }

    @Test
    fun `streamChatCompletion serializes a voice message as an input_audio content part`() = runTest {
        // Regression test: the audio-input part carries raw base64 in "data" (no data-URI
        // prefix, unlike images) plus a separate "format" field, per the OpenAI audio-input shape.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )

        val message = ChatMessageDto(
            role = "user",
            content = MessageContent.Parts(
                listOf(ContentPart.AudioPart(InputAudio(data = "AAAA", format = "wav"))),
            ),
        )

        client.streamChatCompletion("test-key", "gpt-4o-mini", listOf(message), baseUrl).test {
            awaitItem() // Done
            awaitComplete()
        }

        val requestBody = server.takeRequest().body.readUtf8()
        assertTrue(requestBody.contains("\"type\":\"input_audio\""))
        assertTrue(requestBody.contains("\"data\":\"AAAA\""))
        assertTrue(requestBody.contains("\"format\":\"wav\""))
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

        client.streamChatCompletion("test-key", "gpt-4o-mini", listOf(chatMessage("user", "hi")), baseUrl).test {
            assertEquals(StreamEvent.Delta("Hel"), awaitItem())
            assertEquals(StreamEvent.Delta("lo"), awaitItem())
            assertEquals(StreamEvent.Done, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `streamChatCompletion emits reasoning_content as a separate ReasoningDelta event`() = runTest {
        // Reasoning/"thinking" models (e.g. Gemma reasoning variants, DeepSeek-R1-style models)
        // stream their chain-of-thought under "reasoning_content" instead of "content" while
        // they're still "thinking" — surfaced as a distinct event so the UI can show it in a
        // collapsible "thinking" section separate from the final answer.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"Hmm\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"Answer\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )

        client.streamChatCompletion("test-key", "gpt-4o-mini", listOf(chatMessage("user", "hi")), baseUrl).test {
            assertEquals(StreamEvent.ReasoningDelta("Hmm"), awaitItem())
            assertEquals(StreamEvent.Delta("Answer"), awaitItem())
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

        client.streamChatCompletion("test-key", "gpt-4o-mini", listOf(chatMessage("user", "hi")), baseUrl).test {
            assertEquals(StreamEvent.Delta("ok"), awaitItem())
            assertEquals(StreamEvent.Done, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `streamChatCompletion works even when Content-Type is not exactly text-event-stream`() = runTest {
        // Regression test: some self-hosted OpenAI-compatible servers send a correctly-formatted
        // SSE body without the exact "text/event-stream" Content-Type. The previous okhttp-sse
        // based implementation rejected these outright with an opaque "unknown error", even
        // though the API key and URL were both valid — this must now parse successfully.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/plain")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )

        client.streamChatCompletion("test-key", "gpt-4o-mini", listOf(chatMessage("user", "hi")), baseUrl).test {
            assertEquals(StreamEvent.Delta("Hi"), awaitItem())
            assertEquals(StreamEvent.Done, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `streamChatCompletion maps 401 to InvalidApiKey`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"invalid\"}}"),
        )

        client.streamChatCompletion("bad-key", "gpt-4o-mini", listOf(chatMessage("user", "hi")), baseUrl).test {
            val error = awaitError()
            assertTrue(error is OpenAiException.InvalidApiKey)
        }
    }

    @Test
    fun `streamChatCompletion maps malformed baseUrl to BadRequest`() = runTest {
        client.streamChatCompletion(
            apiKey = "test-key",
            model = "gpt-4o-mini",
            messages = listOf(chatMessage("user", "hi")),
            baseUrl = "not a url",
        ).test {
            val error = awaitError()
            assertTrue(error is OpenAiException.BadRequest)
        }
    }

    @Test
    fun `testApiKey succeeds on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = client.testApiKey("test-key", baseUrl)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `testApiKey maps 429 to RateLimited`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody("{\"error\":{\"message\":\"rate limited\"}}"),
        )

        val result = client.testApiKey("test-key", baseUrl)

        assertTrue(result.exceptionOrNull() is OpenAiException.RateLimited)
    }

    @Test
    fun `testApiKey maps 500 to ServerError and surfaces the raw body`() = runTest {
        // Not every OpenAI-compatible server replies with OpenAI's {"error":{"message":...}}
        // shape on failure — the raw body must still reach the user so they can diagnose it.
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal error: model not loaded"))

        val result = client.testApiKey("test-key", baseUrl)

        val error = result.exceptionOrNull()
        assertTrue(error is OpenAiException.ServerError)
        assertTrue((error as OpenAiException.ServerError).serverMessage?.contains("model not loaded") == true)
    }

    @Test
    fun `testApiKey maps malformed baseUrl to BadRequest`() = runTest {
        val result = client.testApiKey("test-key", "not a url")

        assertTrue(result.exceptionOrNull() is OpenAiException.BadRequest)
    }

    @Test
    fun `streamChatCompletion reports the apiKey, not the URL, when the key has invalid header characters`() =
        runTest {
            // Regression test: a non-ASCII apiKey (e.g. stray IME input) used to be misreported
            // as an invalid URL, because both failures surfaced as IllegalArgumentException from
            // the same Request.Builder call chain.
            client.streamChatCompletion(
                apiKey = "あいうえお",
                model = "gpt-4o-mini",
                messages = listOf(chatMessage("user", "hi")),
                baseUrl = baseUrl,
            ).test {
                val error = awaitError()
                assertTrue(error is OpenAiException.BadRequest)
                assertTrue((error as OpenAiException.BadRequest).serverMessage.contains("APIキー"))
            }
        }

    @Test
    fun `testApiKey reports the apiKey, not the URL, when the key has invalid header characters`() = runTest {
        val result = client.testApiKey("あいうえお", baseUrl)

        val error = result.exceptionOrNull()
        assertTrue(error is OpenAiException.BadRequest)
        assertTrue((error as OpenAiException.BadRequest).serverMessage.contains("APIキー"))
    }

    @Test
    fun `testApiKey succeeds when baseUrl is missing a scheme`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        // Users commonly paste a bare "host:port/path" for a self-hosted server and forget
        // the scheme — normalizeBaseUrl should default it to http rather than fail outright.
        val bareHostBaseUrl = baseUrl.removePrefix("http://")

        val result = client.testApiKey("test-key", bareHostBaseUrl)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `generateTitle sends stream=false and returns the trimmed model reply`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "{\"choices\":[{\"message\":{\"content\":\"\\\"Trip Planning Help\\\"\"}}]}",
            ),
        )

        val result = client.generateTitle(
            apiKey = "test-key",
            model = "gpt-4o-mini",
            userMessage = "京都旅行のプランを考えて",
            baseUrl = baseUrl,
        )

        assertTrue(result.isSuccess)
        assertEquals("Trip Planning Help", result.getOrNull())

        val recordedRequest = server.takeRequest()
        val requestBody = recordedRequest.body.readUtf8()
        assertTrue(requestBody.contains("\"stream\":false"))
        assertTrue(requestBody.contains("京都旅行"))
        // Titling is based purely on the user's own message — there's never an assistant turn in
        // the request, so no chat template can misread it as "continue this assistant turn."
        assertTrue(requestBody.contains("\"role\":\"user\""))
        assertTrue(!requestBody.contains("\"role\":\"assistant\""))
    }

    @Test
    fun `generateTitle fails when the server errors`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("model not loaded"))

        val result = client.generateTitle(
            apiKey = "test-key",
            model = "gpt-4o-mini",
            userMessage = "hi",
            baseUrl = baseUrl,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is OpenAiException.ServerError)
    }

    @Test
    fun `normalizeBaseUrl defaults a missing scheme to http and trims whitespace and slashes`() {
        assertEquals("http://example.com/v1", OpenAiApiClient.normalizeBaseUrl("example.com/v1"))
        assertEquals("http://example.com/v1", OpenAiApiClient.normalizeBaseUrl("example.com/v1/"))
        assertEquals("http://example.com/v1", OpenAiApiClient.normalizeBaseUrl("  example.com/v1  "))
        assertEquals("https://example.com/v1", OpenAiApiClient.normalizeBaseUrl("https://example.com/v1"))
    }
}
