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
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * Orchestrates Room persistence, OpenAI streaming, and the current settings for chat
 * conversations. Supports multiple conversations (a lightweight "history") — [observeConversations]
 * lists them, [createNewConversation] starts a fresh one, and each is auto-titled from its first
 * user message.
 */
class ConversationRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val settingsRepository: SettingsRepository,
    private val openAiApiClient: OpenAiApiClient,
) {
    sealed class SendResult {
        object Success : SendResult()
        object ApiKeyMissing : SendResult()
        data class Error(val message: String) : SendResult()
    }

    suspend fun getOrCreateDefaultConversation(): Long {
        conversationDao.getMostRecent()?.let { return it.id }
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
        if (isFirstMessage) {
            conversationDao.updateTitle(conversationId, userText.take(TITLE_MAX_LENGTH))
        }

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
                messageDao.updateContent(placeholderId, accumulated.toString(), accumulatedReasoning.toString().ifBlank { null })
                conversationDao.touch(conversationId, System.currentTimeMillis())
            }
            throw e
        } catch (e: OpenAiException) {
            Log.w(TAG, "sendUserMessage failed: ${e.userMessage}", e)
            streamError = e
        } catch (e: Exception) {
            Log.w(TAG, "sendUserMessage failed with an unexpected exception", e)
            streamError = OpenAiException.Unknown(e)
        }

        val finalReasoning = accumulatedReasoning.toString().ifBlank { null }
        val error = streamError
        return if (error != null) {
            if (accumulated.isEmpty() && finalReasoning == null) {
                messageDao.updateContent(placeholderId, error.userMessage, null, isError = true)
            } else {
                messageDao.updateContent(placeholderId, accumulated.toString(), finalReasoning, isError = false)
            }
            SendResult.Error(error.userMessage)
        } else {
            val finalContent = accumulated.toString()
            if (finalContent.isEmpty() && finalReasoning == null) {
                messageDao.updateContent(placeholderId, "（サーバーからの返答がありませんでした）", null, isError = true)
            } else {
                messageDao.updateContent(placeholderId, finalContent, finalReasoning)
            }
            conversationDao.touch(conversationId, System.currentTimeMillis())
            SendResult.Success
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
    }
}
