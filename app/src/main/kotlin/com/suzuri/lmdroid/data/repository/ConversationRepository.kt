package com.suzuri.lmdroid.data.repository

import android.util.Log
import com.suzuri.lmdroid.data.alarm.DeviceAlarmController
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
import com.suzuri.lmdroid.data.messaging.DeviceMessageController
import com.suzuri.lmdroid.data.notes.DeviceNoteController
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
    private val deviceAlarmController: DeviceAlarmController,
    private val deviceNoteController: DeviceNoteController,
    private val deviceMessageController: DeviceMessageController,
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
        // Lets a caller other than the main chat screen (namely AssistViewModel, via
        // SettingsRepository.currentAssistantSettings()) resolve a different (profile, model) pair
        // than the chat selection — null (the default) keeps ChatViewModel's existing behavior.
        settingsOverride: AppSettings? = null,
    ): SendResult {
        val settings = settingsOverride ?: settingsRepository.currentChatSettings()
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
        // "fetch_webpage", "get_current_location", "set_alarm"/"set_timer", "create_note", and/or
        // "send_message" functions it can decide to call on its own (agentic tool calling), rather
        // than the app deciding unconditionally what to search/fetch/locate/schedule/save/send and
        // force-feeding the results into every request. The harness (this repository) only ever executes one when the model
        // actually asks for it, via executeToolCall() below. maxToolRounds caps how many tool
        // round-trips one reply can make before it's forced to answer with what it has — 0 in
        // Settings means "no user-configured cap", bounded only by SAFETY_MAX_TOOL_ROUNDS so a
        // model that won't stop calling tools can't loop forever.
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
            SET_ALARM_TOOL_NAME -> {
                val hour = extractIntArgument(call.argumentsJson, "hour")
                val minute = extractIntArgument(call.argumentsJson, "minute")
                if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
                    "Error: invalid arguments for $SET_ALARM_TOOL_NAME. Expected JSON like {\"hour\": 7, \"minute\": 30}."
                } else {
                    val label = extractStringArgument(call.argumentsJson, "label")
                    val timeText = "%02d:%02d".format(hour, minute)
                    if (deviceAlarmController.setAlarm(hour, minute, label)) {
                        val summary = "Opened the clock app's alarm screen for $timeText" + (label?.let { " ($it)" } ?: "") +
                            ", waiting for the user to confirm it there."
                        timeline += ThinkingTimelineEntry.ToolActivity(label = "⏰ $timeText", content = summary)
                        summary
                    } else {
                        "Could not set the alarm: no clock app is available on this device."
                    }
                }
            }
            SET_TIMER_TOOL_NAME -> {
                val seconds = extractIntArgument(call.argumentsJson, "seconds")
                if (seconds == null || seconds <= 0) {
                    "Error: invalid arguments for $SET_TIMER_TOOL_NAME. Expected JSON like {\"seconds\": 300}."
                } else {
                    val label = extractStringArgument(call.argumentsJson, "label")
                    if (deviceAlarmController.setTimer(seconds, label)) {
                        val summary = "Opened the clock app's timer screen for $seconds seconds" + (label?.let { " ($it)" } ?: "") +
                            ", waiting for the user to confirm it there."
                        timeline += ThinkingTimelineEntry.ToolActivity(label = "⏱️ ${seconds}秒", content = summary)
                        summary
                    } else {
                        "Could not set the timer: no clock app is available on this device."
                    }
                }
            }
            CREATE_NOTE_TOOL_NAME -> {
                val content = extractStringArgument(call.argumentsJson, "content")
                if (content == null || content.isBlank()) {
                    "Error: invalid arguments for $CREATE_NOTE_TOOL_NAME. Expected JSON like {\"content\": \"...\"}."
                } else {
                    val title = extractStringArgument(call.argumentsJson, "title")
                    val preferredPackage = settingsRepository.currentPreferredNoteAppPackage()
                    if (deviceNoteController.createNote(title, content, preferredPackage)) {
                        val summary = "Opened a note-taking app pre-filled with the memo" + (title?.let { " ($it)" } ?: "") +
                            ", waiting for the user to review and save it there."
                        timeline += ThinkingTimelineEntry.ToolActivity(label = "📝 ${title ?: content.take(20)}", content = summary)
                        summary
                    } else {
                        "Could not create the note: no note-taking app is available on this device."
                    }
                }
            }
            SEND_MESSAGE_TOOL_NAME -> {
                val content = extractStringArgument(call.argumentsJson, "content")
                if (content == null || content.isBlank()) {
                    "Error: invalid arguments for $SEND_MESSAGE_TOOL_NAME. Expected JSON like {\"content\": \"...\"}."
                } else {
                    val preferredPackage = settingsRepository.currentPreferredMessagingAppPackage()
                    if (deviceMessageController.sendMessage(content, preferredPackage)) {
                        val summary = "Opened a messaging app pre-filled with the message, waiting for " +
                            "the user to pick a recipient and send it there."
                        timeline += ThinkingTimelineEntry.ToolActivity(label = "💬 ${content.take(20)}", content = summary)
                        summary
                    } else {
                        "Could not send the message: no messaging app is available on this device."
                    }
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
     * Web検索 is enabled and configured, "get_current_location" when 位置情報 is enabled,
     * "set_alarm"/"set_timer" when アラーム・タイマー is enabled, "create_note" when メモ is enabled,
     * "send_message" when メッセージ is enabled — or null if none of these are, so
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

        if (settingsRepository.currentAlarmToolEnabled()) {
            tools += ToolDefinitionDto(
                function = FunctionSchemaDto(
                    name = SET_ALARM_TOOL_NAME,
                    description = "Set a device alarm for a specific time of day. This opens the " +
                        "clock app's own alarm screen, pre-filled with the requested time, so the " +
                        "user can visually confirm it there — there's no way to create it silently " +
                        "in the background, and the user still needs to confirm/save it on that " +
                        "screen for it to actually take effect. Tell the user to check and confirm " +
                        "it. hour/minute are in this device's local time, 24-hour clock.",
                    parameters = setAlarmToolParameters,
                ),
            )
            tools += ToolDefinitionDto(
                function = FunctionSchemaDto(
                    name = SET_TIMER_TOOL_NAME,
                    description = "Start a device countdown timer for a given duration. This opens " +
                        "the clock app's own timer screen, pre-filled with the requested duration, " +
                        "so the user can visually confirm it there — there's no way to create it " +
                        "silently in the background, and the user still needs to confirm/start it " +
                        "on that screen for it to actually take effect. Tell the user to check and " +
                        "confirm it. Convert whatever duration the user described (e.g. \"5分\", " +
                        "\"an hour and a half\") into total whole seconds yourself.",
                    parameters = setTimerToolParameters,
                ),
            )
        }

        if (settingsRepository.currentNotesToolEnabled()) {
            tools += ToolDefinitionDto(
                function = FunctionSchemaDto(
                    name = CREATE_NOTE_TOOL_NAME,
                    description = "Save a memo/note. This opens the user's chosen note-taking app " +
                        "(Settings → メモ) — or, if none is chosen, a share menu to pick one — " +
                        "pre-filled with the given text, so the user can visually confirm it there " +
                        "— there's no way to save it silently in the background, and the user still " +
                        "needs to confirm/save it in that app for it to actually take effect. Tell " +
                        "the user to check and confirm it.",
                    parameters = createNoteToolParameters,
                ),
            )
        }

        if (settingsRepository.currentMessagingToolEnabled()) {
            tools += ToolDefinitionDto(
                function = FunctionSchemaDto(
                    name = SEND_MESSAGE_TOOL_NAME,
                    description = "Send a message via LINE, SMS, or another messaging app. This " +
                        "opens the user's chosen messaging app (Settings → メッセージ) — or, if none " +
                        "is chosen, a share menu to pick one — pre-filled with the given text; the " +
                        "user still has to pick who to send it to (there's no way to address a " +
                        "specific recipient from here) and confirm sending it there. Tell the user " +
                        "which app opened and to pick the recipient and send it.",
                    parameters = sendMessageToolParameters,
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

    /** Pulls an integer argument out of a tool call's raw JSON arguments, e.g. `key="hour"` from `{"hour":7}`. */
    private fun extractIntArgument(argumentsJson: String, key: String): Int? {
        val jsonObject = runCatching { json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject ?: return null
        return (jsonObject[key] as? JsonPrimitive)?.content?.toIntOrNull()
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
        const val SET_ALARM_TOOL_NAME = "set_alarm"
        const val SET_TIMER_TOOL_NAME = "set_timer"
        const val CREATE_NOTE_TOOL_NAME = "create_note"
        const val SEND_MESSAGE_TOOL_NAME = "send_message"

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

        val setAlarmToolParameters: JsonElement = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "hour" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("integer"),
                                "description" to JsonPrimitive("Hour of the day, 0-23 (24-hour clock, e.g. 7 for 7am, 19 for 7pm)."),
                            ),
                        ),
                        "minute" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("integer"),
                                "description" to JsonPrimitive("Minute of the hour, 0-59."),
                            ),
                        ),
                        "label" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("Optional short label for the alarm, e.g. \"薬を飲む\"."),
                            ),
                        ),
                    ),
                ),
                "required" to JsonArray(listOf(JsonPrimitive("hour"), JsonPrimitive("minute"))),
            ),
        )

        val setTimerToolParameters: JsonElement = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "seconds" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("integer"),
                                "description" to JsonPrimitive("Total duration of the timer in whole seconds (e.g. 300 for 5 minutes)."),
                            ),
                        ),
                        "label" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("Optional short label for the timer, e.g. \"パスタ\"."),
                            ),
                        ),
                    ),
                ),
                "required" to JsonArray(listOf(JsonPrimitive("seconds"))),
            ),
        )

        val createNoteToolParameters: JsonElement = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "title" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("Optional short title for the note."),
                            ),
                        ),
                        "content" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("The note's body text."),
                            ),
                        ),
                    ),
                ),
                "required" to JsonArray(listOf(JsonPrimitive("content"))),
            ),
        )

        val sendMessageToolParameters: JsonElement = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "content" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("The message text to send."),
                            ),
                        ),
                    ),
                ),
                "required" to JsonArray(listOf(JsonPrimitive("content"))),
            ),
        )
    }
}
