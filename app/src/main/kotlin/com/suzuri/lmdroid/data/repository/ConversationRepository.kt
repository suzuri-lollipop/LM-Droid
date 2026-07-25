package com.suzuri.lmdroid.data.repository

import android.util.Log
import com.suzuri.lmdroid.data.db.ConversationDao
import com.suzuri.lmdroid.data.db.ConversationEntity
import com.suzuri.lmdroid.data.db.MessageDao
import com.suzuri.lmdroid.data.db.MessageEntity
import com.suzuri.lmdroid.data.db.MessageRole
import com.suzuri.lmdroid.data.network.ChatMessageDto
import com.suzuri.lmdroid.data.network.OpenAiApiClient
import com.suzuri.lmdroid.data.network.OpenAiException
import com.suzuri.lmdroid.data.network.StreamEvent
import com.suzuri.lmdroid.data.settings.AppSettings
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates Room persistence, OpenAI streaming, and the current settings for chat
 * conversations. Supports multiple conversations (a lightweight "history") — [observeConversations]
 * lists them, [createNewConversation] starts a fresh one, and each is auto-titled by the model
 * itself from its first exchange. [editMessageAndRegenerate] supports ChatGPT/Claude-style
 * "edit a past message and regenerate from there."
 */
class ConversationRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val settingsRepository: SettingsRepository,
    private val openAiApiClient: OpenAiApiClient,
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
     * whatever you were last talking about. Reuses the most recent conversation only if it's
     * still empty (e.g. the app was closed again without sending anything), so repeatedly
     * relaunching without chatting doesn't pile up empty "新しい会話" rows in the history.
     */
    suspend fun getOrCreateDefaultConversation(): Long {
        val mostRecent = conversationDao.getMostRecent()
        if (mostRecent != null && messageDao.countMessages(mostRecent.id) == 0) {
            return mostRecent.id
        }
        return createNewConversation()
    }

    suspend fun createNewConversation(): Long {
        val now = System.currentTimeMillis()
        return conversationDao.insert(ConversationEntity(title = DEFAULT_TITLE, createdAt = now, updatedAt = now))
    }

    suspend fun deleteConversation(conversationId: Long) {
        conversationDao.delete(conversationId)
    }

    fun observeConversations(): Flow<List<ConversationEntity>> = conversationDao.observeAll()

    fun observeConversation(conversationId: Long): Flow<ConversationEntity?> =
        conversationDao.observeConversation(conversationId)

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        messageDao.observeMessages(conversationId)

    suspend fun sendUserMessage(conversationId: Long, userText: String): SendResult {
        val settings = settingsRepository.currentSettings()
        val apiKey = settings.apiKey
        if (apiKey.isNullOrBlank()) {
            return SendResult.ApiKeyMissing
        }

        val isFirstMessage = messageDao.getMessages(conversationId).isEmpty()

        val sentAt = System.currentTimeMillis()
        messageDao.insert(
            MessageEntity(conversationId = conversationId, role = MessageRole.USER, content = userText, createdAt = sentAt),
        )
        conversationDao.touch(conversationId, sentAt)
        // No fallback title set here: the conversation keeps its DEFAULT_TITLE ("新しい会話")
        // until the LLM-generated title lands below. It's now shown live in the Chat top bar, so
        // briefly echoing the user's own message back at them as the "title" reads as a glitch.

        return generateAssistantReply(conversationId, apiKey, settings, isFirstMessage, userText)
    }

    /**
     * Edits a previously-sent user message in place, discards every message that came after it,
     * and regenerates the assistant's reply from that point — the same "edit and regenerate"
     * pattern used by ChatGPT/Claude.
     */
    suspend fun editMessageAndRegenerate(conversationId: Long, messageId: Long, newText: String): SendResult {
        val settings = settingsRepository.currentSettings()
        val apiKey = settings.apiKey
        if (apiKey.isNullOrBlank()) {
            return SendResult.ApiKeyMissing
        }

        messageDao.updateContent(messageId, newText)
        messageDao.deleteMessagesAfter(conversationId, messageId)
        conversationDao.touch(conversationId, System.currentTimeMillis())

        val isFirstMessage = messageDao.getMessages(conversationId).size == 1

        return generateAssistantReply(conversationId, apiKey, settings, isFirstMessage, newText)
    }

    /**
     * Regenerates an assistant reply in place: discards that reply (and anything after it, in
     * case it wasn't the last message) and re-asks using the same prompt history, without
     * requiring the user to edit their own message first.
     */
    suspend fun regenerateResponse(conversationId: Long, assistantMessageId: Long): SendResult {
        val settings = settingsRepository.currentSettings()
        val apiKey = settings.apiKey
        if (apiKey.isNullOrBlank()) {
            return SendResult.ApiKeyMissing
        }

        messageDao.deleteMessagesAfter(conversationId, assistantMessageId)
        messageDao.deleteMessage(assistantMessageId)

        val remaining = messageDao.getMessages(conversationId)
        val isFirstMessage = remaining.size == 1
        val latestUserText = remaining.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()

        return generateAssistantReply(conversationId, apiKey, settings, isFirstMessage, latestUserText)
    }

    private suspend fun generateAssistantReply(
        conversationId: Long,
        apiKey: String,
        settings: AppSettings,
        isFirstMessage: Boolean,
        latestUserText: String,
    ): SendResult {
        val placeholderId = messageDao.insert(
            MessageEntity(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = "",
                createdAt = System.currentTimeMillis(),
            ),
        )

        val allMessages = messageDao.getMessages(conversationId)
            .filter { it.content.isNotBlank() && !it.isError }

        val history = mutableListOf<ChatMessageDto>()
        if (allMessages.isNotEmpty()) {
            var currentRole = allMessages[0].role
            val currentContent = StringBuilder(allMessages[0].content)

            for (i in 1 until allMessages.size) {
                val msg = allMessages[i]
                if (msg.role == currentRole) {
                    currentContent.append("\n\n").append(msg.content)
                } else {
                    history.add(ChatMessageDto(role = currentRole.toApiRole(), content = currentContent.toString()))
                    currentRole = msg.role
                    currentContent.setLength(0)
                    currentContent.append(msg.content)
                }
            }
            history.add(ChatMessageDto(role = currentRole.toApiRole(), content = currentContent.toString()))
        }

        val accumulated = StringBuilder()
        val accumulatedReasoning = StringBuilder()
        var lastFlushAt = 0L
        var streamError: OpenAiException? = null

        suspend fun flushIfDue() {
            val now = System.currentTimeMillis()
            if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {
                messageDao.updateContent(placeholderId, accumulated.toString(), accumulatedReasoning.toString().ifBlank { null })
                lastFlushAt = now
            }
        }

        try {
            openAiApiClient.streamChatCompletion(apiKey, settings.model, history, settings.baseUrl).collect { event ->
                when (event) {
                    is StreamEvent.Delta -> {
                        accumulated.append(event.text)
                        flushIfDue()
                    }
                    is StreamEvent.ReasoningDelta -> {
                        accumulatedReasoning.append(event.text)
                        flushIfDue()
                    }
                    StreamEvent.Done -> Unit
                }
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
                messageDao.updateContent(placeholderId, stoppedContent, accumulatedReasoning.toString().ifBlank { null })
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

        val finalReasoning = accumulatedReasoning.toString().ifBlank { null }
        val error = streamError
        if (error != null) {
            if (accumulated.isEmpty() && finalReasoning == null) {
                messageDao.updateContent(placeholderId, error.userMessage, null, isError = true)
            } else {
                messageDao.updateContent(placeholderId, accumulated.toString(), finalReasoning, isError = false)
            }
            return SendResult.Error(error.userMessage)
        }

        val finalContent = accumulated.toString()
        if (finalContent.isEmpty() && finalReasoning == null) {
            messageDao.updateContent(placeholderId, "（サーバーからの返答がありませんでした）", null, isError = true)
        } else {
            messageDao.updateContent(placeholderId, finalContent, finalReasoning)
        }
        conversationDao.touch(conversationId, System.currentTimeMillis())

        if (isFirstMessage) {
            // Fire-and-forget: the user shouldn't wait on this extra round-trip just to see their
            // answer. On failure, the conversation just keeps its DEFAULT_TITLE.
            val assistantTextForTitle = finalContent.ifBlank { null }
            backgroundScope.launch {
                openAiApiClient.generateTitle(apiKey, settings.model, latestUserText, assistantTextForTitle, settings.baseUrl)
                    .onSuccess { title -> conversationDao.updateTitle(conversationId, title.take(TITLE_MAX_LENGTH)) }
                    .onFailure { e -> Log.w(TAG, "Title generation failed, keeping the fallback title", e) }
            }
        }

        return SendResult.Success
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
    }
}
