package com.suzuri.lmdroid.data.network

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    fun `streamChatCompletion omits tool_calls, tool_call_id and tools for a plain request`() = runTest {
        // Regression test: this app's Json config has encodeDefaults=true (needed to keep
        // "stream" and "max_tokens" from being dropped — see the test above), which would
        // otherwise also serialize every message with an explicit "tool_calls":null and
        // "tool_call_id":null, plus a top-level "tools":null, on every single request — even
        // when no tool-calling feature is in use at all. Several self-hosted OpenAI-compatible
        // servers return HTTP 500 when a message carries fields their request schema doesn't
        // defensively handle being null, so these three must stay fully absent unless actually set.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )

        client.streamChatCompletion("test-key", "gpt-4o-mini", listOf(chatMessage("user", "hi")), baseUrl).test {
            awaitItem() // Done
            awaitComplete()
        }

        val requestBody = server.takeRequest().body.readUtf8()
        assertTrue(!requestBody.contains("tool_calls"))
        assertTrue(!requestBody.contains("tool_call_id"))
        assertTrue(!requestBody.contains("\"tools\""))
    }

    @Test
    fun `streamChatCompletion omits chat_template_kwargs when thinking is left at its default`() = runTest {
        // A plain OpenAI (or other) server that has never heard of llama.cpp's
        // chat_template_kwargs extension must not see it on every request just because this app's
        // 思考 toggle exists — only an explicit override (enableThinking = false) should send it.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )

        client.streamChatCompletion("test-key", "gpt-4o-mini", listOf(chatMessage("user", "hi")), baseUrl).test {
            awaitItem() // Done
            awaitComplete()
        }

        val requestBody = server.takeRequest().body.readUtf8()
        assertTrue(!requestBody.contains("chat_template_kwargs"))
    }

    @Test
    fun `streamChatCompletion sends chat_template_kwargs enable_thinking=false when thinking is disabled`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )

        client.streamChatCompletion(
            "test-key", "gpt-4o-mini", listOf(chatMessage("user", "hi")), baseUrl, enableThinking = false,
        ).test {
            awaitItem() // Done
            awaitComplete()
        }

        val requestBody = server.takeRequest().body.readUtf8()
        assertTrue(requestBody.contains("\"chat_template_kwargs\":{\"enable_thinking\":false}"))
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
    fun `streamChatCompletion reassembles a tool call streamed across multiple fragments`() = runTest {
        // OpenAI streams a tool call's id/name up front, then trickles the JSON "arguments"
        // string in one chunk at a time — this must all be reassembled into a single
        // RequestedToolCall, not surfaced piecemeal.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"web_search\",\"arguments\":\"\"}}]}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"ab\"}}]}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"c123\"}}]}}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )

        client.streamChatCompletion("test-key", "gpt-4o-mini", listOf(chatMessage("user", "hi")), baseUrl).test {
            val event = awaitItem()
            assertTrue(event is StreamEvent.ToolCallsRequested)
            val toolCalls = (event as StreamEvent.ToolCallsRequested).toolCalls
            assertEquals(1, toolCalls.size)
            assertEquals("call_1", toolCalls[0].id)
            assertEquals("web_search", toolCalls[0].name)
            assertEquals("abc123", toolCalls[0].argumentsJson)
            assertEquals(StreamEvent.Done, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `streamChatCompletion accumulates multiple simultaneous tool calls separately by index`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[" +
                        "{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"web_search\",\"arguments\":\"{}\"}}," +
                        "{\"index\":1,\"id\":\"call_2\",\"type\":\"function\",\"function\":{\"name\":\"web_search\",\"arguments\":\"{}\"}}" +
                        "]}}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )

        client.streamChatCompletion("test-key", "gpt-4o-mini", listOf(chatMessage("user", "hi")), baseUrl).test {
            val event = awaitItem()
            assertTrue(event is StreamEvent.ToolCallsRequested)
            val toolCalls = (event as StreamEvent.ToolCallsRequested).toolCalls
            assertEquals(listOf("call_1", "call_2"), toolCalls.map { it.id })
            assertEquals(StreamEvent.Done, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `streamChatCompletion includes the tools array in the request body when provided`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )

        val tool = ToolDefinitionDto(
            function = FunctionSchemaDto(
                name = "web_search",
                description = "Search the web.",
                parameters = JsonObject(mapOf("type" to JsonPrimitive("object"))),
            ),
        )

        client.streamChatCompletion(
            "test-key", "gpt-4o-mini", listOf(chatMessage("user", "hi")), baseUrl, tools = listOf(tool),
        ).test {
            awaitItem() // Done
            awaitComplete()
        }

        val requestBody = server.takeRequest().body.readUtf8()
        assertTrue(requestBody.contains("\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"web_search\""))
    }

    @Test
    fun `streamChatCompletion serializes an assistant tool-call message and a tool result message`() = runTest {
        // Regression test for the OpenAI tool-calling wire shape: an assistant message that only
        // requests a tool call has null content plus a "tool_calls" array, and the follow-up
        // "tool" role message carries its result tagged by "tool_call_id" rather than "content"
        // alone.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )

        val messages = listOf(
            chatMessage("user", "what's the weather in kyoto?"),
            ChatMessageDto(
                role = "assistant",
                toolCalls = listOf(
                    ToolCallDto(id = "call_1", function = FunctionCallDto(name = "web_search", arguments = "{\"query\":\"kyoto weather\"}")),
                ),
            ),
            ChatMessageDto(role = "tool", content = MessageContent.Text("sunny today"), toolCallId = "call_1"),
        )

        client.streamChatCompletion("test-key", "gpt-4o-mini", messages, baseUrl).test {
            awaitItem() // Done
            awaitComplete()
        }

        val requestBody = server.takeRequest().body.readUtf8()
        assertTrue(requestBody.contains("\"tool_calls\":[{\"id\":\"call_1\""))
        assertTrue(requestBody.contains("\"tool_call_id\":\"call_1\""))
        assertTrue(requestBody.contains("\"content\":\"sunny today\""))
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
    fun `listModels extracts model ids from the data array`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "{\"data\":[{\"id\":\"gpt-4o-mini\"},{\"id\":\"gpt-4o\"}]}",
            ),
        )

        val result = client.listModels("test-key", baseUrl)

        assertTrue(result.isSuccess)
        assertEquals(listOf("gpt-4o-mini", "gpt-4o"), result.getOrNull())
    }

    @Test
    fun `listModels succeeds with an empty list when the server genuinely has none`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"data\":[]}"))

        val result = client.listModels("test-key", baseUrl)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<String>(), result.getOrNull())
    }

    @Test
    fun `listModels fails rather than silently returning an empty list when the response shape is unrecognized`() =
        runTest {
            // Regression test: a 200 response whose body doesn't parse into the expected
            // {"data": [...]} shape (e.g. a server with a nonstandard /models format) used to
            // silently resolve to Result.success(emptyList()) — indistinguishable from "this
            // provider genuinely has zero models" — which left a saved profile with no models
            // registered and no visible error explaining why.
            server.enqueue(MockResponse().setResponseCode(200).setBody("not valid json at all"))

            val result = client.listModels("test-key", baseUrl)

            assertTrue(result.isFailure)
        }

    @Test
    fun `listModels maps a non-2xx response to a failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"invalid\"}}"))

        val result = client.listModels("bad-key", baseUrl)

        assertTrue(result.exceptionOrNull() is OpenAiException.InvalidApiKey)
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
