package com.suzuri.lmdroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query(
        "UPDATE messages SET content = :content, reasoningContent = :reasoningContent, isError = :isError " +
            "WHERE id = :id",
    )
    suspend fun updateContent(id: Long, content: String, reasoningContent: String? = null, isError: Boolean = false)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun getMessages(conversationId: Long): List<MessageEntity>

    /**
     * Used when editing a past user message: discards everything that came after it so the
     * reply can be regenerated from that point.
     */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id > :afterMessageId")
    suspend fun deleteMessagesAfter(conversationId: Long, afterMessageId: Long)

    /** Used when regenerating an assistant reply in place, to discard the old one before re-asking. */
    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: Long)
}
