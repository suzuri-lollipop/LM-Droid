package com.suzuri.lmdroid.data.repository

import android.util.Log
import com.suzuri.lmdroid.data.attachment.AttachmentFileStore
import com.suzuri.lmdroid.data.attachment.SavedAttachment
import com.suzuri.lmdroid.data.db.ConversationDao
import com.suzuri.lmdroid.data.db.ConversationEntity
import com.suzuri.lmdroid.data.db.FolderDao
import com.suzuri.lmdroid.data.db.FolderEntity
import com.suzuri.lmdroid.data.db.MessageAttachmentDao
import com.suzuri.lmdroid.data.db.MessageAttachmentEntity
import com.suzuri.lmdroid.data.db.MessageDao
import com.suzuri.lmdroid.data.db.MessageEntity
import com.suzuri.lmdroid.data.db.MessageRole
import com.suzuri.lmdroid.data.db.MessageWithAttachments
import com.suzuri.lmdroid.data.db.ThinkingTimelineEntry
import com.suzuri.lmdroid.data.location.DeviceLocationProvider
import com.suzuri.lmdroid.data.network.ChatMessageDto
import com.suzuri.lmdroid.data.network.ContentPart
import com.suzuri.lmdroid.data.network.FunctionCallDto
import com.suzuri.lmdroid.data.network.FunctionSchemaDto
import com.suzuri.lmdroid.data.network.ImageUrl
import com.suzuri.lmdroid.data.network.InputAudio
import com.suzuri.lmdroid.data.network.MessageContent
import com.suzuri.lmdroid.data.network.OpenAiApiClient
import com.suzuri.lmdroid.data.network.OpenAiException
import com.suzuri.lmdroid.data.network.RequestedToolCall
import com.suzuri.lmdroid.data.network.StreamEvent
import com.suzuri.lmdroid.data.network.ToolCallDto
import com.suzuri.lmdroid.data.network.ToolDefinitionDto
import com.suzuri.lmdroid.data.network.chatMessage
import com.suzuri.lmdroid.data.settings.AppSettings
import com.suzuri.lmdroid.data.settings.SettingsRepository
import com.suzuri.lmdroid.data.websearch.BraveSearchClient
import com.suzuri.lmdroid.data.websearch.WebPageFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Orchestrates Room persistence, OpenAI streaming, and the current settings for chat
 * conversations. Supports multiple conversations (a lightweight "history") — [observeConversations]
 * lists them, [createNewConversation] starts a fresh one, and each is auto-titled by the model
 * itself from the user's first message (see [generateTitleInBackground]). [editMessageAndRegenerate] supports ChatGPT/Claude-style
 * "edit a past message and regenerate from there." Conversations can also be organized into
 * user-created folders (e.g. "お気に入り") — see [observeFolders], [createFolder], and
 * [setConversationFolder]; a conversation belongs to at most one folder.
 */
class ConversationRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val messageAttachmentDao: MessageAttachmentDao,
    private val folderDao: FolderDao,
    private val settingsRepository: SettingsRepository,
    private val openAiApiClient: OpenAiApiClient,
    private val attachmentFileStore: AttachmentFileStore,
    private val braveSearchClient: BraveSearchClient,
    private val webPageFetcher: WebPageFetcher,
    private val deviceLocationProvider: DeviceLocationProvider,
    private val systemPromptRepository: SystemPromptRepository,
    private val json: Json,
) {
    // For best-effort background work (auto-titling) that shouldn't make the caller wait for the
    // main reply, and should still finish even if the caller's own scope gets cancelled/torn down.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    sealed class SendResult {
        object Success : SendResult()
        object ApiKeyMissing : SendResult()
        data class Error(val message: String) : SendResult()
    }

    /**
     * Called once when the app cold-starts (ChatViewModel's init), so it should land on a blank
     * conversation — like Claude/ChatGPT/Gemini opening to a fresh chat rather than resuming
     * whatever you were last talking about. Reuses the most recent conversation if it's still
     * empty (e.g. the app was closed again without sending anything); otherwise returns null,
     * meaning "show a fresh, not-yet-persisted new conversation" — nothing is written to the
     * database (and nothing shows up in History) until the user actually sends a first message.
     */
    suspend fun getInitialConversationId(): Long? {
        val mostRecent = conversationDao.getMostRecent()
        if (mostRecent != null && messageDao.countMessages(mostRecent.id) == 0) {
            return mostRecent.id
        }
        return null
    }

    suspend fun createNewConversation(): Long {
        val now = System.currentTimeMillis()
        return conversationDao.insert(ConversationEntity(title = DEFAULT_TITLE, createdAt = now, updatedAt = now))
    }

    /** Also deletes any attached images' files on disk — otherwise they'd become permanently orphaned. */
    suspend fun deleteConversation(conversationId: Long) {
        val messageIds = messageDao.getMessages(conversationId).map { it.id }
        val filePaths = if (messageIds.isNotEmpty()) {
            messageAttachmentDao.getForMessages(messageIds).map { it.filePath }
        } else {
            emptyList()
        }
        conversationDao.delete(conversationId)
        if (filePaths.isNotEmpty()) {
            attachmentFileStore.deleteAll(filePaths)
        }
    }

    fun observeConversations(): Flow<List<ConversationEntity>> = conversationDao.observeAll()

    fun observeConversation(conversationId: Long): Flow<ConversationEntity?> =
        conversationDao.observeConversation(conversationId)

    fun observeMessages(conversationId: Long): Flow<List<MessageWithAttachments>> =
        messageDao.observeMessagesWithAttachments(conversationId)

    fun observeFolders(): Flow<List<FolderEntity>> = folderDao.observeAll()

    fun observeConversationsInFolder(folderId: Long): Flow<List<ConversationEntity>> =
        conversationDao.observeByFolder(folderId)

    suspend fun createFolder(name: String): Long {
        val trimmed = name.trim()
        return folderDao.insert(FolderEntity(name = trimmed, createdAt = System.currentTimeMillis()))
    }

    suspend fun renameFolder(folderId: Long, name: String) {
        folderDao.rename(folderId, name.trim())
    }

    /** Deleting a folder unfiles its conversations (see ConversationEntity's SET_NULL foreign key) rather than deleting them. */
    suspend fun deleteFolder(folderId: Long) {
        folderDao.delete(folderId)
    }

    suspend fun setConversationFolder(conversationId: Long, folderId: Long?) {
        conversationDao.setFolder(conversationId, folderId)
    }

    /**
     * Generates prompt-suggestion chips from the topics of past conversations (their titles), for
     * the empty/new-conversation screen. Returns null when there's no API key, no conversation
     * history to base suggestions on, or generation fails — callers should fall back to static
     * suggestions in that case.
     */
    suspend fun generateSuggestedPrompts(): List<String>? {
        val settings = settingsRepository.currentSystemSettings()
        val apiKey = settings.apiKey
        if (apiKey.isNullOrBlank()) return null

        val topics = conversationDao.getRecent(RECENT_CONVERSATIONS_FOR_SUGGESTIONS)
            .map { it.title }
            .filter { it.isNotBlank() && it != DEFAULT_TITLE }
            .distinct()
        if (topics.isEmpty()) return null

        return openAiApiClient.generateSuggestedPrompts(apiKey, settings.model, topics, settings.baseUrl)
            .onFailure { e -> Log.w(TAG, "Suggested prompt generation failed, falling back to static suggestions", e) }
            .getOrNull()
    }

    suspend fun sendUserMessage(
        conversationId: Long,
        userText: String,
        attachments: List<SavedAttachment> = emptyList(),
    ): SendResult {
        val settings = settingsRepository.currentChatSettings()
        val apiKey = settings.apiKey
        if (apiKey.isNullOrBlank()) {
            return SendResult.ApiKeyMissing
        }

        val isFirstMessage = messageDao.getMessages(conversationId).isEmpty()

        val sentAt = System.currentTimeMillis()
        val messageId = messageDao.insert(
            MessageEntity(conversationId = conversationId, role = MessageRole.USER, content = userText, createdAt = sentAt),
        )
        if (attachments.isNotEmpty()) {
            messageAttachmentDao.insertAll(
                attachments.map { attachment ->
                    MessageAttachmentEntity(
                        messageId = messageId,
                        filePath = attachment.filePath,
                        mimeType = attachment.mimeType,
                        createdAt = sentAt,
                    )
                },
            )
        }
        conversationDao.touch(conversationId, sentAt)
        // No fallback title set here: the conversation keeps its DEFAULT_TITLE ("新しい会話")
        // until the LLM-generated title lands below. It's now shown live in the Chat top bar, so
        // briefly echoing the user's own message back at them as the "title" reads as a glitch.
        if (isFirstMessage) {
            generateTitleInBackground(conversationId, userText)
        }

        return generateAssistantReply(conversationId, apiKey, settings)
    }

    /**
     * Edits a previously-sent user message in place, discards every message that came after it,
     * and regenerates the assistant's reply from that point — the same "edit and regenerate"
     * pattern used by ChatGPT/Claude.
     */
    suspend fun editMessageAndRegenerate(conversationId: Long, messageId: Long, newText: String): SendResult {
        val settings = settingsRepository.currentChatSettings()
        val apiKey = settings.apiKey
        if (apiKey.isNullOrBlank()) {
            return SendResult.ApiKeyMissing
        }

        messageDao.updateContent(messageId, newText)
        messageDao.deleteMessagesAfter(conversationId, messageId)
        conversationDao.touch(conversationId, System.currentTimeMillis())

        val isFirstMessage = messageDao.getMessages(conversationId).size == 1
        if (isFirstMessage) {
            // The edited text may describe a different topic than the original — re-title from it.
            generateTitleInBackground(conversationId, newText)
        }

        return generateAssistantReply(conversationId, apiKey, settings)
    }

    /**
     * Regenerates an assistant reply in place: discards that reply (and anything after it, in
     * case it wasn't the last message) and re-asks using the same prompt history, without
     * requiring the user to edit their own message first.
     */
    suspend fun regenerateResponse(conversationId: Long, assistantMessageId: Long): SendResult {
        val settings = settingsRepository.currentChatSettings()
        val apiKey = settings.apiKey
        if (apiKey.isNullOrBlank()) {
            return SendResult.ApiKeyMissing
        }

        messageDao.deleteMessagesAfter(conversationId, assistantMessageId)
        messageDao.deleteMessage(assistantMessageId)

        return generateAssistantReply(conversationId, apiKey, settings)
    }

    private suspend fun generateAssistantReply(
        conversationId: Long,
        apiKey: String,
        settings: AppSettings,
    ): SendResult {
        val placeholderId = messageDao.insert(
            MessageEntity(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = "",
                createdAt = System.currentTimeMillis(),
            ),
        )

        val allMessages = messageDao.getMessagesWithAttachments(conversationId)
            .filter { !it.message.isError && (it.message.content.isNotBlank() || it.attachments.isNotEmpty()) }

        val history = mutableListOf<ChatMessageDto>()
        if (allMessages.isNotEmpty()) {
            var currentRole = allMessages[0].message.role
            val currentContent = StringBuilder(allMessages[0].message.content)
            val currentAttachments = allMessages[0].attachments.toMutableList()

            suspend fun flushSegment() {
                history.add(buildChatMessageDto(currentRole, currentContent.toString(), currentAttachments))
            }

            for (i in 1 until allMessages.size) {
                val entry = allMessages[i]
                if (entry.message.role == currentRole) {
                    if (currentContent.isNotEmpty() && entry.message.content.isNotBlank()) {
                        currentContent.append("\n\n")
                    }
                    currentContent.append(entry.message.content)
                    currentAttachments += entry.attachments
                } else {
                    flushSegment()
                    currentRole = entry.message.role
                    currentContent.setLength(0)
                    currentContent.append(entry.message.content)
                    currentAttachments.clear()
                    currentAttachments += entry.attachments
                }
            }
            flushSegment()
        }

        // Models have no internal clock and a fixed training cutoff, so without this they have no
        // way to resolve "today"/"tomorrow"/"next week", or to judge whether a piece of
        // information (e.g. a web search result) is current or years out of date — exactly the
        // failure mode that caused stale, wrong-year forecasts to get treated as current before.
        history.add(0, chatMessage("system", currentDateSystemPrompt()))

        // Every saved system prompt the user has selected as active (zero or more) — applies to
        // every conversation, not persisted as part of any one message, added fresh as leading
        // messages on every request rather than written into message history.
        systemPromptRepository.currentActiveContents().forEachIndexed { index, content ->
            history.add(1 + index, chatMessage("system", content))
        }

        // Tools: when enabled and configured in Settings, the model is offered "web_search",
        // "fetch_webpage" and/or "get_current_location" functions it can decide to call on its own
        // (agentic tool calling), rather than the app deciding unconditionally what to search/
        // fetch/locate and force-feeding the results into every request. The harness (this
        // repository) only ever executes one when the model actually asks for it, via
        // executeToolCall() below. maxToolRounds caps how many tool round-trips one reply can make
        // before it's forced to answer with what it has — 0 in Settings means "no user-configured
        // cap", bounded only by SAFETY_MAX_TOOL_ROUNDS so a model that won't stop calling tools
        // can't loop forever.
        val tools = availableTools()
        val configuredMaxRounds = settingsRepository.currentWebSearchMaxToolRounds()
        val maxToolRounds = if (configuredMaxRounds <= 0) {
            SAFETY_MAX_TOOL_ROUNDS
        } else {
            configuredMaxRounds.coerceAtMost(SAFETY_MAX_TOOL_ROUNDS)
        }
        // Chain-of-thought and tool activity, in the exact order they happen (Claude-style),
        // rather than two separate fixed blocks — a run of consecutive reasoning deltas coalesces
        // into one entry; each tool call always starts a new one. See ThinkingTimelineEntry.
        val timeline = mutableListOf<ThinkingTimelineEntry>()

        fun appendReasoning(text: String) {
            val last = timeline.lastOrNull()
            if (last is ThinkingTimelineEntry.Reasoning) {
                timeline[timeline.lastIndex] = last.copy(text = last.text + text)
            } else {
                timeline += ThinkingTimelineEntry.Reasoning(text)
            }
        }

        fun timelineJson(): String? = timeline.takeIf { it.isNotEmpty() }
            ?.let { json.encodeToString(ListSerializer(ThinkingTimelineEntry.serializer()), it) }

        val accumulated = StringBuilder()
        var lastFlushAt = 0L
        var streamError: OpenAiException? = null

        suspend fun flushIfDue(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (force || now - lastFlushAt >= FLUSH_INTERVAL_MS) {
                messageDao.updateContent(placeholderId, accumulated.toString(), timelineJson())
                lastFlushAt = now
            }
        }

        /** Runs the tool call the model requested, recording it on the timeline, and returns the result text for the matching "tool" role message. */
        suspend fun executeToolCall(call: RequestedToolCall): String = when (call.name) {
            WEB_SEARCH_TOOL_NAME -> {
                val query = extractStringArgument(call.argumentsJson, "query")
                if (query == null) {
                    "Error: invalid arguments for $WEB_SEARCH_TOOL_NAME. Expected JSON like {\"query\": \"...\"}."
                } else {
                    val resultText = performWebSearch(query)
                    if (resultText == null) {
                        "No web results were found for \"$query\"."
                    } else {
                        timeline += ThinkingTimelineEntry.ToolActivity(label = "🔍 \"$query\"", content = resultText)
                        resultText
                    }
                }
            }
            FETCH_WEBPAGE_TOOL_NAME -> {
                val url = extractStringArgument(call.argumentsJson, "url")
                if (url == null) {
                    "Error: invalid arguments for $FETCH_WEBPAGE_TOOL_NAME. Expected JSON like {\"url\": \"...\"}."
                } else {
                    val content = webPageFetcher.fetchTextContent(url)
                        .onFailure { e -> Log.w(TAG, "Fetching webpage failed: $url", e) }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                    if (content == null) {
                        "Could not fetch readable content from \"$url\"."
                    } else {
                        timeline += ThinkingTimelineEntry.ToolActivity(label = "🌐 \"$url\"", content = content)
                        content
                    }
                }
            }
            GET_LOCATION_TOOL_NAME -> {
                val location = deviceLocationProvider.getCurrentLocation()
                if (location == null) {
                    "The device's current location is unavailable right now (permission not granted, location services off, or no fix could be obtained)."
                } else {
                    val resultText = buildString {
                        append("Latitude: ${location.latitude}, Longitude: ${location.longitude}")
                        if (location.address != null) append("\nApproximate address: ${location.address}")
                    }
                    timeline += ThinkingTimelineEntry.ToolActivity(label = "📍 現在地を取得", content = resultText)
                    resultText
                }
            }
            else -> "Error: unknown tool \"${call.name}\"."
        }

        try {
            var round = 0
            while (true) {
                val offerTools = tools != null && round < maxToolRounds
                val requestedToolCalls = mutableListOf<RequestedToolCall>()
                openAiApiClient.streamChatCompletion(
                    apiKey = apiKey,
                    model = settings.model,
                    messages = history,
                    baseUrl = settings.baseUrl,
                    tools = if (offerTools) tools else null,
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Delta -> {
                            accumulated.append(event.text)
                            flushIfDue()
                        }
                        is StreamEvent.ReasoningDelta -> {
                            appendReasoning(event.text)
                            flushIfDue()
                        }
                        is StreamEvent.ToolCallsRequested -> requestedToolCalls += event.toolCalls
                        StreamEvent.Done -> Unit
                    }
                }

                if (requestedToolCalls.isEmpty()) break
                round++

                // Echo the model's own tool-call request back before the results, as the OpenAI
                // tool-calling protocol requires, then answer each with a "tool" role message.
                history += ChatMessageDto(
                    role = "assistant",
                    toolCalls = requestedToolCalls.map { call ->
                        ToolCallDto(id = call.id, function = FunctionCallDto(name = call.name, arguments = call.argumentsJson))
                    },
                )
                requestedToolCalls.forEach { call ->
                    val resultText = executeToolCall(call)
                    history += ChatMessageDto(role = "tool", content = MessageContent.Text(resultText), toolCallId = call.id)
                }
                flushIfDue(force = true)
            }
        } catch (e: CancellationException) {
            // The user tapped "stop". The Job is already cancelled at this point, so the cleanup
            // writes below need NonCancellable or they'd themselves throw immediately — then
            // rethrow so cancellation keeps propagating; it must never be swallowed as a regular
            // error.
            withContext(NonCancellable) {
                val stoppedContent = buildString {
                    append(accumulated)
                    if (isNotEmpty()) append("\n\n")
                    append(STOPPED_NOTICE)
                }
                messageDao.updateContent(placeholderId, stoppedContent, timelineJson())
                conversationDao.touch(conversationId, System.currentTimeMillis())
            }
            throw e
        } catch (e: OpenAiException) {
            Log.w(TAG, "generateAssistantReply failed: ${e.userMessage}", e)
            streamError = e
        } catch (e: Exception) {
            Log.w(TAG, "generateAssistantReply failed with an unexpected exception", e)
            streamError = OpenAiException.Unknown(e)
        }

        val finalTimelineJson = timelineJson()
        val error = streamError
        if (error != null) {
            if (accumulated.isEmpty() && finalTimelineJson == null) {
                messageDao.updateContent(placeholderId, error.userMessage, null, isError = true)
            } else {
                messageDao.updateContent(placeholderId, accumulated.toString(), finalTimelineJson, isError = false)
            }
            return SendResult.Error(error.userMessage)
        }

        val finalContent = accumulated.toString()
        if (finalContent.isEmpty() && finalTimelineJson == null) {
            messageDao.updateContent(placeholderId, "（サーバーからの返答がありませんでした）", null, isError = true)
        } else {
            messageDao.updateContent(placeholderId, finalContent, finalTimelineJson)
        }
        conversationDao.touch(conversationId, System.currentTimeMillis())

        return SendResult.Success
    }

    /**
     * A segment with no attachments keeps the plain-string wire format; one with any becomes a
     * content-parts array — images as "image_url" (Vision), voice recordings as "input_audio"
     * (Audio). A server whose model doesn't support one of these simply rejects the request; there
     * is no reliable way to know a model's supported modalities ahead of time from a generic
     * OpenAI-compatible /models listing, so that error just surfaces to the user as-is.
     */
    private suspend fun buildChatMessageDto(
        role: MessageRole,
        text: String,
        attachments: List<MessageAttachmentEntity>,
    ): ChatMessageDto {
        if (attachments.isEmpty()) {
            return chatMessage(role.toApiRole(), text)
        }
        val parts = mutableListOf<ContentPart>()
        if (text.isNotBlank()) {
            parts += ContentPart.TextPart(text)
        }
        attachments.forEach { attachment ->
            if (attachment.mimeType.startsWith("audio/")) {
                val base64 = attachmentFileStore.readAsBase64(attachment.filePath)
                val format = attachment.mimeType.substringAfter('/')
                parts += ContentPart.AudioPart(InputAudio(data = base64, format = format))
            } else {
                val dataUri = attachmentFileStore.readAsDataUri(SavedAttachment(attachment.filePath, attachment.mimeType))
                parts += ContentPart.ImagePart(ImageUrl(dataUri))
            }
        }
        return ChatMessageDto(role = role.toApiRole(), content = MessageContent.Parts(parts))
    }

    /**
     * The function definitions offered to the model this turn — "web_search"/"fetch_webpage" when
     * Web検索 is enabled and configured, "get_current_location" when 位置情報 is enabled — or null
     * if neither is, so
     * [ChatCompletionRequest.tools][com.suzuri.lmdroid.data.network.ChatCompletionRequest] is
     * omitted entirely rather than sent as an empty/useless list.
     */
    private suspend fun availableTools(): List<ToolDefinitionDto>? {
        val tools = mutableListOf<ToolDefinitionDto>()

        if (settingsRepository.currentBraveSearchEnabled() && !settingsRepository.currentWebSearchApiKey().isNullOrBlank()) {
            tools += ToolDefinitionDto(
                function = FunctionSchemaDto(
                    name = WEB_SEARCH_TOOL_NAME,
                    description = "Search the live web for up-to-date information — current events, " +
                        "facts you're unsure of, or anything that may have changed since your training " +
                        "data. Call this whenever it would improve the accuracy of your answer. Results " +
                        "are short snippets — use $FETCH_WEBPAGE_TOOL_NAME on a promising URL to read more. " +
                        "Results often mix pages from different years (e.g. an old cached forecast or " +
                        "article alongside a current one) — check each result's own date/content against " +
                        "today's date (see the system message) and prefer whichever is actually current; " +
                        "don't assume the top result is the most recent one.",
                    parameters = webSearchToolParameters,
                ),
            )
            tools += ToolDefinitionDto(
                function = FunctionSchemaDto(
                    name = FETCH_WEBPAGE_TOOL_NAME,
                    description = "Fetch a specific web page by URL and read its visible text content — " +
                        "use this when a $WEB_SEARCH_TOOL_NAME result's snippet isn't enough detail, or " +
                        "when the user gives you a URL directly. Only static, server-rendered text can " +
                        "be read; content a page fills in dynamically via JavaScript after it loads " +
                        "(e.g. some sites' live weather figures) may not be captured.",
                    parameters = fetchWebpageToolParameters,
                ),
            )
        }

        if (settingsRepository.currentLocationEnabled()) {
            tools += ToolDefinitionDto(
                function = FunctionSchemaDto(
                    name = GET_LOCATION_TOOL_NAME,
                    description = "Get the user's current device location — approximate latitude/" +
                        "longitude and, when available, a human-readable address. Call this when the " +
                        "user's question depends on where they currently are (e.g. local weather, " +
                        "nearby places, time zone) and they haven't already told you a location.",
                    parameters = noArgumentsToolParameters,
                ),
            )
        }

        Log.d(TAG, "availableTools: ${tools.map { it.function.name }} (locationEnabled=${settingsRepository.currentLocationEnabled()})")
        return tools.takeIf { it.isNotEmpty() }
    }

    /** Grounds the model in today's actual date — see the call site in [generateAssistantReply] for why this is unconditional. */
    private fun currentDateSystemPrompt(): String {
        val today = LocalDate.now()
        val isoDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val dayOfWeek = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return "Today's date is $isoDate ($dayOfWeek). Use this as the ground truth for any relative " +
            "date reasoning (\"today\", \"tomorrow\", \"next week\", etc.), and to judge whether " +
            "information you encounter — including web search or fetched page results — is current or outdated."
    }

    /** Pulls a string argument out of a tool call's raw JSON arguments, e.g. `key="query"` from `{"query":"kyoto weather"}`. */
    private fun extractStringArgument(argumentsJson: String, key: String): String? {
        val jsonObject = runCatching { json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject ?: return null
        return (jsonObject[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /**
     * Runs a single Brave web search for [query] — called only when the model itself requests the
     * "web_search" tool (see the tool-call loop in [generateAssistantReply]), never unconditionally.
     * Returns null (never surfaced as an error to the user) when unconfigured or the search itself
     * fails; the caller feeds that back to the model as "no results" so it can still finish
     * answering from its own knowledge instead of the whole reply failing.
     */
    private suspend fun performWebSearch(query: String): String? {
        val apiKey = settingsRepository.currentWebSearchApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Web search skipped: no Brave Search API key configured")
            return null
        }
        val results = braveSearchClient.search(apiKey, query)
            .onFailure { e -> Log.w(TAG, "Brave web search failed, continuing without it", e) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return buildString {
            results.forEachIndexed { index, result ->
                append("${index + 1}. ${result.title}\n${result.description}\n(${result.url})")
                if (index != results.lastIndex) append("\n\n")
            }
        }
    }

    /**
     * Fire-and-forget: titles the conversation from the user's own message, without waiting on
     * the assistant's reply (see [OpenAiApiClient.generateTitle]) — it's launched right alongside
     * [generateAssistantReply] rather than after it, so the title can land while the assistant is
     * still streaming. On failure, the conversation just keeps its DEFAULT_TITLE. Uses the
     * "system" model (Settings → システム) rather than whatever's active for chat — it falls back
     * to the chat selection when no system-specific override is configured.
     */
    private fun generateTitleInBackground(conversationId: Long, userText: String) {
        backgroundScope.launch {
            val systemSettings = settingsRepository.currentSystemSettings()
            val systemApiKey = systemSettings.apiKey
            if (systemApiKey.isNullOrBlank()) {
                Log.w(TAG, "Title generation skipped: no system model configured")
                return@launch
            }
            openAiApiClient.generateTitle(systemApiKey, systemSettings.model, userText, systemSettings.baseUrl)
                .onSuccess { title -> conversationDao.updateTitle(conversationId, title.take(TITLE_MAX_LENGTH)) }
                .onFailure { e -> Log.w(TAG, "Title generation failed, keeping the fallback title", e) }
        }
    }

    private fun MessageRole.toApiRole(): String = when (this) {
        MessageRole.SYSTEM -> "system"
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
    }

    private companion object {
        const val TAG = "ConversationRepository"
        const val FLUSH_INTERVAL_MS = 150L
        const val TITLE_MAX_LENGTH = 40
        const val DEFAULT_TITLE = "新しい会話"
        const val STOPPED_NOTICE = "（生成を停止しました）"
        const val RECENT_CONVERSATIONS_FOR_SUGGESTIONS = 15
        const val WEB_SEARCH_TOOL_NAME = "web_search"
        const val FETCH_WEBPAGE_TOOL_NAME = "fetch_webpage"
        const val GET_LOCATION_TOOL_NAME = "get_current_location"

        // A hard ceiling regardless of the user's own Settings → Web検索 configuration (where 0
        // means "no cap") — purely a safety valve against a truly runaway model that never stops
        // calling the tool, not a limit users are meant to bump into in practice.
        const val SAFETY_MAX_TOOL_ROUNDS = 50

        val webSearchToolParameters: JsonElement = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "query" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("The search query to send."),
                            ),
                        ),
                    ),
                ),
                "required" to JsonArray(listOf(JsonPrimitive("query"))),
            ),
        )

        val fetchWebpageToolParameters: JsonElement = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "url" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("The full URL of the page to fetch, e.g. one returned by $WEB_SEARCH_TOOL_NAME."),
                            ),
                        ),
                    ),
                ),
                "required" to JsonArray(listOf(JsonPrimitive("url"))),
            ),
        )

        // get_current_location takes no arguments — still needs an (empty) object schema, since
        // the tool-calling protocol requires "parameters" to be a valid JSON Schema object.
        val noArgumentsToolParameters: JsonElement = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(emptyMap()),
            ),
        )
    }
}
