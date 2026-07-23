package com.suzuri.lmdroid.data.repository

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * Orchestrates Room persistence, OpenAI streaming, and the current settings for a single
 * ongoing conversation. There is intentionally no multi-conversation/thread-list support in v1 —
 * see the "conversationId" plumbing below, which is the seam a future thread-list feature would use.
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
        val now = System.currentTimeMillis()
        return conversationDao.insert(ConversationEntity(title = "Chat", createdAt = now, updatedAt = now))
    }

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        messageDao.observeMessages(conversationId)

    suspend fun sendUserMessage(conversationId: Long, userText: String): SendResult {
        val settings = settingsRepository.currentSettings()
        val apiKey = settings.apiKey
        if (apiKey.isNullOrBlank()) {
            return SendResult.ApiKeyMissing
        }

        val sentAt = System.currentTimeMillis()
        messageDao.insert(
            MessageEntity(conversationId = conversationId, role = MessageRole.USER, content = userText, createdAt = sentAt),
        )
        conversationDao.touch(conversationId, sentAt)

        val placeholderId = messageDao.insert(
            MessageEntity(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = "",
                createdAt = System.currentTimeMillis(),
            ),
        )

        val history = messageDao.getMessages(conversationId).map {
            ChatMessageDto(role = it.role.toApiRole(), content = it.content)
        }

        val accumulated = StringBuilder()
        var lastFlushAt = 0L
        var streamError: OpenAiException? = null

        try {
            openAiApiClient.streamChatCompletion(apiKey, settings.model, history).collect { event ->
                when (event) {
                    is StreamEvent.Delta -> {
                        accumulated.append(event.text)
                        val now = System.currentTimeMillis()
                        if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {
                            messageDao.updateContent(placeholderId, accumulated.toString())
                            lastFlushAt = now
                        }
                    }
                    StreamEvent.Done -> Unit
                }
            }
        } catch (e: OpenAiException) {
            streamError = e
        } catch (e: Exception) {
            streamError = OpenAiException.Unknown(e)
        }

        val error = streamError
        return if (error != null) {
            if (accumulated.isEmpty()) {
                messageDao.updateContent(placeholderId, error.userMessage, isError = true)
            } else {
                messageDao.updateContent(placeholderId, accumulated.toString(), isError = false)
            }
            SendResult.Error(error.userMessage)
        } else {
            messageDao.updateContent(placeholderId, accumulated.toString())
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
        const val FLUSH_INTERVAL_MS = 150L
    }
}
