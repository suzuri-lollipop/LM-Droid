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
import com.suzuri.lmdroid.data.db.SkillEntity
import com.suzuri.lmdroid.data.db.ThinkingTimelineEntry
import com.suzuri.lmdroid.data.location.DeviceLocationProvider
import com.suzuri.lmdroid.data.messaging.DeviceMessageController
import com.suzuri.lmdroid.data.music.DeviceMusicController
import com.suzuri.lmdroid.data.music.YouTubeDataApiClient
import com.suzuri.lmdroid.data.notes.DeviceNoteController
import com.suzuri.lmdroid.data.network.ChatMessageDto
import com.suzuri.lmdroid.data.network.ContentPart
import com.suzuri.lmdroid.data.network.FunctionCallDto
import com.suzuri.lmdroid.data.network.FunctionSchemaDto
import com.suzuri.lmdroid.data.network.ImageUrl
import com.suzuri.lmdroid.data.network.InputAudio
import com.suzuri.lmdroid.data.network.MessageContent
import com.suzuri.lmdroid.data.network.ImageGenerationParams
import com.suzuri.lmdroid.data.network.ImageGenerationState
import com.suzuri.lmdroid.data.network.ImageGenerator
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * How long ChatViewModel (and, when in Settings → アシスタント "読み上げをバックグラウンドで続けながら
 * 開く", AssistViewModel) waits after a reply is shown before firing its
 * [ConversationRepository.SendResult]'s deferred tool side effects — just long enough for the
 * screen/speech pipeline to actually catch up with the DB write, not a meaningful "wait."
 */
const val TOOL_SIDE_EFFECT_DELAY_MS = 500L

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
    private val imageGenerationRepository: ImageGenerationRepository,
    private val attachmentFileStore: AttachmentFileStore,
    private val braveSearchClient: BraveSearchClient,
    private val webPageFetcher: WebPageFetcher,
    private val deviceLocationProvider: DeviceLocationProvider,
    private val deviceAlarmController: DeviceAlarmController,
    private val deviceNoteController: DeviceNoteController,
    private val deviceMessageController: DeviceMessageController,
    private val deviceMusicController: DeviceMusicController,
    private val youTubeDataApiClient: YouTubeDataApiClient,
    private val systemPromptRepository: SystemPromptRepository,
    private val skillRepository: SkillRepository,
    private val json: Json,
) {
    // For best-effort background work (auto-titling) that shouldn't make the caller wait for the
    // main reply, and should still finish even if the caller's own scope gets cancelled/torn down.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    sealed class SendResult {
        /** [pendingSideEffects] are the deferred tool-launch actions (see the tool-call loop in [generateAssistantReply]) queued this turn but not yet fired — the caller (ChatViewModel/AssistViewModel) decides when to actually invoke them, now that the reply is showing. */
        data class Success(val pendingSideEffects: List<() -> Unit> = emptyList()) : SendResult()
        object ApiKeyMissing : SendResult()
        data class Error(val message: String, val pendingSideEffects: List<() -> Unit> = emptyList()) : SendResult()
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
        // A skill the user explicitly picked for this one message (see SkillDialog's "使う"
        // action), rather than left for the model to discover on its own via the "use_skill"
        // tool — forces that skill's full content into context for just this reply, regardless
        // of whether it's in the active/advertised set. null (the default) is the normal path,
        // where the model decides for itself from the active skills' catalog.
        forcedSkillId: Long? = null,
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

        return generateAssistantReply(conversationId, apiKey, settings, forcedSkillId)
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
        forcedSkillId: Long? = null,
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
        val systemPromptContents = systemPromptRepository.currentActiveContents()
        systemPromptContents.forEachIndexed { index, content ->
            history.add(1 + index, chatMessage("system", content))
        }
        // Where the next leading system message goes — right after the date and every system
        // prompt just inserted above — tracked as a running index so skill messages below can
        // insert in a stable, readable order instead of both fighting over the same position.
        var leadingSystemMessageIndex = 1 + systemPromptContents.size

        // Chain-of-thought and tool activity, in the exact order they happen (Claude-style),
        // rather than two separate fixed blocks — a run of consecutive reasoning deltas coalesces
        // into one entry; each tool call always starts a new one. See ThinkingTimelineEntry.
        // Declared here (rather than alongside the streaming state further below) so the forced
        // skill injection right after this can also record its own entry on it.
        val timeline = mutableListOf<ThinkingTimelineEntry>()

        // Skills: each active one (Settings → スキル, or per-conversation via SkillDialog) is a
        // named bundle of instructions. Only its name/description is always visible to the model,
        // as a compact catalog below — the full content is loaded on demand, either by the model
        // itself via the "use_skill" tool (see availableTools()/executeToolCall()) or, when
        // [forcedSkillId] is set, forced into context here regardless of whether that skill is
        // even part of the active set — see SkillDialog's "使う" action and ChatViewModel's
        // pendingForcedSkillId, the explicit, user-driven counterpart to the model's own discovery.
        val activeSkills = skillRepository.currentActiveSkills()
        Log.d(TAG, "skills: forcedSkillId=$forcedSkillId active=${activeSkills.map { it.name }}")
        if (forcedSkillId != null) {
            val forcedSkill = skillRepository.getSkill(forcedSkillId)
            if (forcedSkill != null) {
                history.add(
                    leadingSystemMessageIndex++,
                    chatMessage(
                        "system",
                        "The user explicitly invoked the \"${forcedSkill.name}\" skill for this message. " +
                            "Follow these instructions when composing your reply:\n\n${forcedSkill.content}",
                    ),
                )
                timeline += ThinkingTimelineEntry.ToolActivity(
                    label = "🧩 ${forcedSkill.name}",
                    content = "ユーザーがこの会話で明示的に呼び出しました。",
                )
            }
        }
        if (activeSkills.isNotEmpty()) {
            val catalog = activeSkills.joinToString("\n") { skill ->
                "- ${skill.name}: ${skill.description.ifBlank { "(説明未設定)" }}"
            }
            history.add(
                leadingSystemMessageIndex++,
                chatMessage(
                    "system",
                    "You have access to the following skills — named bundles of specialized " +
                        "instructions. When the user's request matches one, call the $USE_SKILL_TOOL_NAME " +
                        "tool with its exact name to load its full instructions before answering; you may " +
                        "call it more than once if several apply. Do not call it for requests none of " +
                        "these describe.\n\n$catalog",
                ),
            )
        }

        // Tools: when enabled and configured in Settings, the model is offered "web_search",
        // "fetch_webpage", "get_current_location", "set_alarm"/"set_timer", "create_note",
        // "send_message", "play_music", and/or "use_skill" functions it can decide to call on its
        // own (agentic tool calling), rather than the app deciding unconditionally what to
        // search/fetch/locate/schedule/save/send/play/load and force-feeding the results into
        // every request. The harness (this repository) only ever executes one when the model
        // actually asks for it, via executeToolCall() below. maxToolRounds caps how many tool
        // round-trips one reply can make before it's forced to answer with what it has — 0 in
        // Settings means "no user-configured cap", bounded only by SAFETY_MAX_TOOL_ROUNDS so a
        // model that won't stop calling tools can't loop forever.
        val tools = availableTools(activeSkills)
        val configuredMaxRounds = settingsRepository.currentWebSearchMaxToolRounds()
        val maxToolRounds = if (configuredMaxRounds <= 0) {
            SAFETY_MAX_TOOL_ROUNDS
        } else {
            configuredMaxRounds.coerceAtMost(SAFETY_MAX_TOOL_ROUNDS)
        }

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

        // Tools that open another app (set_alarm/set_timer/create_note/send_message) queue their
        // actual Intent launch here instead of firing it the moment the model calls the tool —
        // otherwise, e.g., the share chooser for send_message would pop up and steal the screen
        // before the model has even generated the reply text that explains what it's doing,
        // especially jarring in the voice Assistant overlay. On normal completion or a stream
        // error these are handed back via SendResult for the caller (ChatViewModel/AssistViewModel)
        // to fire once it's actually shown/spoken the reply — only AssistViewModel knows about
        // speech playback timing, which is a UI-layer concern this repository has no visibility
        // into. The one exception is cancellation (below): the caller's own coroutine is being torn
        // down right along with this one at that point, so there's no one left to hand off to —
        // fireSideEffectsAfterDelay() there fires them itself instead.
        val pendingSideEffects = mutableListOf<() -> Unit>()

        suspend fun fireSideEffectsAfterDelay() {
            if (pendingSideEffects.isEmpty()) return
            delay(TOOL_SIDE_EFFECT_DELAY_MS)
            val actions = pendingSideEffects.toList()
            pendingSideEffects.clear()
            actions.forEach { action -> runCatching(action).onFailure { e -> Log.w(TAG, "Deferred tool side effect failed", e) } }
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
                    val launchAlarm = deviceAlarmController.prepareSetAlarm(hour, minute, label)
                    if (launchAlarm != null) {
                        pendingSideEffects += launchAlarm
                        val summary = "Will open the clock app's alarm screen for $timeText" + (label?.let { " ($it)" } ?: "") +
                            " right after this reply is shown, for the user to confirm it there."
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
                    val launchTimer = deviceAlarmController.prepareSetTimer(seconds, label)
                    if (launchTimer != null) {
                        pendingSideEffects += launchTimer
                        val summary = "Will open the clock app's timer screen for $seconds seconds" + (label?.let { " ($it)" } ?: "") +
                            " right after this reply is shown, for the user to confirm it there."
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
                    val launchNote = deviceNoteController.prepareCreateNote(title, content, preferredPackage)
                    if (launchNote != null) {
                        pendingSideEffects += launchNote
                        val summary = "Will open a note-taking app pre-filled with the memo" + (title?.let { " ($it)" } ?: "") +
                            " right after this reply is shown, for the user to review and save it there."
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
                    val launchMessage = deviceMessageController.prepareSendMessage(content, preferredPackage)
                    if (launchMessage != null) {
                        pendingSideEffects += launchMessage
                        val summary = "Will open a messaging app pre-filled with the message right after " +
                            "this reply is shown, for the user to pick a recipient and send it there."
                        timeline += ThinkingTimelineEntry.ToolActivity(label = "💬 ${content.take(20)}", content = summary)
                        summary
                    } else {
                        "Could not send the message: no messaging app is available on this device."
                    }
                }
            }
            PLAY_MUSIC_TOOL_NAME -> {
                val query = extractStringArgument(call.argumentsJson, "query")
                if (query == null || query.isBlank()) {
                    "Error: invalid arguments for $PLAY_MUSIC_TOOL_NAME. Expected JSON like {\"query\": \"...\"}."
                } else {
                    val wantsPlaylist = extractStringArgument(call.argumentsJson, "type") == "playlist"
                    val preferredPackage = settingsRepository.currentPreferredMusicAppPackage()
                    val genericFocus = if (wantsPlaylist) DeviceMusicController.FOCUS_ALBUM else DeviceMusicController.FOCUS_AUDIO
                    // YouTube Music's own ACTION_MEDIA_PLAY_FROM_SEARCH handling only opens its
                    // search screen without actually playing anything (a known YouTube Music
                    // limitation, confirmed by hand — see DeviceMusicController's doc comment), so
                    // when a YouTube Data API key is configured and either no preferred app is set
                    // or YouTube Music itself is the preferred one, resolve the query to a specific
                    // video/playlist id first and open that directly instead.
                    val youtubeApiKey = settingsRepository.currentYoutubeDataApiKey()
                    val wantsYoutubeMusic = preferredPackage == null || preferredPackage == DeviceMusicController.YOUTUBE_MUSIC_PACKAGE
                    val launchMusic = if (!youtubeApiKey.isNullOrBlank() && wantsYoutubeMusic) {
                        if (wantsPlaylist) {
                            val playlistId = youTubeDataApiClient.searchPlaylistId(youtubeApiKey, query)
                                .onFailure { e -> Log.w(TAG, "YouTube Data API playlist search failed, falling back to generic search intent", e) }
                                .getOrNull()
                            // A bare playlist URL only opens its browsing page without starting
                            // playback — the first track's own video id is what turns this into a
                            // "watch this, then continue through the rest" URL that actually
                            // autoplays (see DeviceMusicController.prepareOpenYoutubeMusicPlaylist).
                            val firstVideoId = playlistId?.let {
                                youTubeDataApiClient.getFirstPlaylistItemVideoId(youtubeApiKey, it)
                                    .onFailure { e -> Log.w(TAG, "Fetching playlist's first track failed, opening the playlist without autoplay", e) }
                                    .getOrNull()
                            }
                            playlistId?.let { deviceMusicController.prepareOpenYoutubeMusicPlaylist(it, firstVideoId) }
                                ?: deviceMusicController.preparePlayMusic(query, preferredPackage, genericFocus)
                        } else {
                            val videoId = youTubeDataApiClient.searchVideoId(youtubeApiKey, query)
                                .onFailure { e -> Log.w(TAG, "YouTube Data API search failed, falling back to generic search intent", e) }
                                .getOrNull()
                            videoId?.let { deviceMusicController.prepareOpenYoutubeMusicTrack(it) }
                                ?: deviceMusicController.preparePlayMusic(query, preferredPackage, genericFocus)
                        }
                    } else {
                        deviceMusicController.preparePlayMusic(query, preferredPackage, genericFocus)
                    }
                    if (launchMusic != null) {
                        pendingSideEffects += launchMusic
                        val summary = "Will open a music app and start playing \"$query\" right after " +
                            "this reply is shown."
                        timeline += ThinkingTimelineEntry.ToolActivity(label = "🎵 $query", content = summary)
                        summary
                    } else {
                        "Could not play music: no music app is available on this device."
                    }
                }
            }
            GENERATE_IMAGE_TOOL_NAME -> {
                val prompt = extractStringArgument(call.argumentsJson, "prompt")
                if (prompt == null || prompt.isBlank()) {
                    "Error: invalid arguments for $GENERATE_IMAGE_TOOL_NAME. Expected JSON like {\"prompt\": \"...\"}."
                } else {
                    val negativePrompt = extractStringArgument(call.argumentsJson, "negative_prompt")
                    val width = extractIntArgument(call.argumentsJson, "width") ?: 512
                    val height = extractIntArgument(call.argumentsJson, "height") ?: 512
                    
                    val params = ImageGenerationParams(
                        prompt = prompt,
                        negativePrompt = negativePrompt,
                        width = width,
                        height = height
                    )
                    
                    var resultText = "Image generation failed."
                    imageGenerationRepository.generateImage(params).collect { state ->
                        when (state) {
                            is ImageGenerationState.Loading -> {
                                timeline += ThinkingTimelineEntry.ToolActivity(
                                    label = "🎨 画像生成中",
                                    content = state.message ?: "生成中..."
                                )
                            }
                            is ImageGenerationState.Success -> {
                                val urls = state.imageUrls
                                val savedAttachments = mutableListOf<SavedAttachment>()
                                urls.forEach { url ->
                                    val saved = if (url.startsWith("data:")) {
                                        attachmentFileStore.saveBase64(url)
                                    } else {
                                        // Remote URL - need to download and save
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                val bytes = URL(url).readBytes()
                                                attachmentFileStore.save(bytes, "image/png")
                                            }.getOrNull()
                                        }
                                    }
                                    saved?.let { savedAttachments.add(it) }
                                }
                                
                                if (savedAttachments.isNotEmpty()) {
                                    val now = System.currentTimeMillis()
                                    messageAttachmentDao.insertAll(savedAttachments.map { saved ->
                                        MessageAttachmentEntity(
                                            messageId = placeholderId,
                                            filePath = saved.filePath,
                                            mimeType = saved.mimeType,
                                            createdAt = now
                                        )
                                    })
                                    resultText = "Successfully generated ${savedAttachments.size} image(s). They have been attached to your message."
                                    timeline += ThinkingTimelineEntry.ToolActivity(
                                        label = "🎨 生成完了",
                                        content = resultText
                                    )
                                }
                            }
                            is ImageGenerationState.Error -> {
                                resultText = "Error: ${state.message}"
                            }
                            else -> {}
                        }
                    }
                    resultText
                }
            }
            USE_SKILL_TOOL_NAME -> {
                val name = extractStringArgument(call.argumentsJson, "name")
                if (name == null || name.isBlank()) {
                    "Error: invalid arguments for $USE_SKILL_TOOL_NAME. Expected JSON like {\"name\": \"...\"}."
                } else {
                    val skill = activeSkills.find { it.name.equals(name, ignoreCase = true) }
                    if (skill == null) {
                        "Error: no skill named \"$name\" is available. Available skills: " +
                            activeSkills.joinToString(", ") { it.name }
                    } else {
                        timeline += ThinkingTimelineEntry.ToolActivity(
                            label = "🧩 ${skill.name}",
                            content = "スキルの内容を読み込みました。",
                        )
                        skill.content
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
                    thinkingEffort = settings.thinkingEffort,
                    memoryEnabled = settings.memoryEnabled,
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

                // Whatever text the model streamed before deciding to call a tool is preamble
                // ("let me check your location…"), not the answer — the post-tool round answers
                // from the top. Keeping it would show/speak it first and then restart the reply,
                // and leave both concatenated in the saved message; discard it (UI and speech
                // progress reset on the shrink — see AssistViewModel.resetSpokenIndexIfContentReplaced)
                // so only the actual post-tool answer is shown and spoken.
                if (accumulated.isNotEmpty()) {
                    accumulated.setLength(0)
                    messageDao.updateContent(placeholderId, "", timelineJson())
                }

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
                fireSideEffectsAfterDelay()
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
            return SendResult.Error(error.userMessage, pendingSideEffects.toList())
        }

        val finalContent = accumulated.toString()
        if (finalContent.isEmpty() && finalTimelineJson == null) {
            messageDao.updateContent(placeholderId, "（サーバーからの返答がありませんでした）", null, isError = true)
        } else {
            messageDao.updateContent(placeholderId, finalContent, finalTimelineJson)
        }
        conversationDao.touch(conversationId, System.currentTimeMillis())

        return SendResult.Success(pendingSideEffects.toList())
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
     * "send_message" when メッセージ is enabled, "play_music" when 音楽 is enabled, "use_skill" when
     * at least one skill (Settings → スキル) is active — or null if none of these are, so
     * [ChatCompletionRequest.tools][com.suzuri.lmdroid.data.network.ChatCompletionRequest] is
     * omitted entirely rather than sent as an empty/useless list.
     */
    private suspend fun availableTools(activeSkills: List<SkillEntity>): List<ToolDefinitionDto>? {
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
                    // Strong "must call" wording: without it the model would sometimes answer a
                    // weather question with "I don't know where you are" instead of just calling
                    // this tool first.
                    description = "Get the user's current device location — approximate latitude/" +
                        "longitude and, when available, a human-readable address. You have no other " +
                        "way to know where the user is, so whenever their question depends on their " +
                        "location (weather, nearby places, local time/time zone, anything about " +
                        "\"here\") and they haven't explicitly named a location themselves, you MUST " +
                        "call this tool first and answer from its result — never reply that you " +
                        "don't know their location without calling it. For live conditions such as " +
                        "the current weather, follow up with web_search using the obtained location.",
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

        if (settingsRepository.currentMusicToolEnabled()) {
            tools += ToolDefinitionDto(
                function = FunctionSchemaDto(
                    name = PLAY_MUSIC_TOOL_NAME,
                    description = "Play music. This opens the user's chosen music app (Settings → " +
                        "音楽) — or, if none is chosen, whichever music app the system resolves it " +
                        "to — and starts playing whatever matches the given search query. If the " +
                        "user asks for a specific song, use \"song\" (the default) and put that " +
                        "song's title and artist in the query. If they only name an artist or genre " +
                        "with no specific song (e.g. \"play Dua Lipa\"), still use \"song\" but pick " +
                        "one well-known track yourself and put both the title and artist in the " +
                        "query (e.g. \"Levitating by Dua Lipa\") rather than passing just the " +
                        "artist/genre name — a bare artist/genre query is much less likely to " +
                        "actually start playback (some music apps only show search results for it " +
                        "instead of playing anything). If the user asks for a whole album or an " +
                        "existing playlist (their own or a curated one, e.g. \"play the Renaissance " +
                        "album\" or \"play my workout playlist\"), use \"playlist\" instead so the " +
                        "whole thing plays rather than a single track. There's no way to control " +
                        "playback (pause/skip/volume) from here once it starts.",
                    parameters = playMusicToolParameters,
                ),
            )
        }

        // Always available if the active profile's provider is an image generation service
        // (or we can just offer it and the repository handles the check).
        tools += ToolDefinitionDto(
            function = FunctionSchemaDto(
                name = GENERATE_IMAGE_TOOL_NAME,
                description = "Generate an image from a text prompt (text-to-image). " +
                    "Explain what the user wants to see in detail. You can also specify a negative " +
                    "prompt for things you don't want to see. The generated image will be " +
                    "attached to your response.",
                parameters = generateImageToolParameters,
            ),
        )

        if (activeSkills.isNotEmpty()) {
            tools += ToolDefinitionDto(
                function = FunctionSchemaDto(
                    name = USE_SKILL_TOOL_NAME,
                    description = "Load the full instructions for one of your available skills " +
                        "(see the skills catalog in the system message) by its exact name. Call " +
                        "this before following a skill's instructions — you only see its short " +
                        "description until you do.",
                    parameters = useSkillToolParameters,
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
        const val PLAY_MUSIC_TOOL_NAME = "play_music"
        const val GENERATE_IMAGE_TOOL_NAME = "generate_image"
        const val USE_SKILL_TOOL_NAME = "use_skill"

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

        val playMusicToolParameters: JsonElement = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "query" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive(
                                    "What to play, as free text. For type=\"song\", prefer \"song " +
                                        "title by artist\" (e.g. \"levitating by dua lipa\") over a " +
                                        "bare artist/genre name. For type=\"playlist\", the album " +
                                        "name plus artist (e.g. \"Renaissance by Beyoncé\") or the " +
                                        "playlist's name (\"my discover weekly playlist\", \"90s " +
                                        "city pop\").",
                                ),
                            ),
                        ),
                        "type" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "enum" to JsonArray(listOf(JsonPrimitive("song"), JsonPrimitive("playlist"))),
                                "description" to JsonPrimitive(
                                    "\"song\" (default) plays one specific track. \"playlist\" " +
                                        "plays a whole album or existing playlist instead — use it " +
                                        "when the user asked for the whole thing, not just one track.",
                                ),
                            ),
                        ),
                    ),
                ),
                "required" to JsonArray(listOf(JsonPrimitive("query"))),
            ),
        )

        val generateImageToolParameters: JsonElement = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "prompt" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("Detailed description of the image to generate."),
                            ),
                        ),
                        "negative_prompt" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("Description of what to avoid in the image."),
                            ),
                        ),
                        "width" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("integer"),
                                "description" to JsonPrimitive("Image width in pixels (default 512)."),
                            ),
                        ),
                        "height" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("integer"),
                                "description" to JsonPrimitive("Image height in pixels (default 512)."),
                            ),
                        ),
                    ),
                ),
                "required" to JsonArray(listOf(JsonPrimitive("prompt"))),
            ),
        )

        val useSkillToolParameters: JsonElement = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "name" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("The exact name of the skill to load, as listed in the skills catalog."),
                            ),
                        ),
                    ),
                ),
                "required" to JsonArray(listOf(JsonPrimitive("name"))),
            ),
        )
    }
}
