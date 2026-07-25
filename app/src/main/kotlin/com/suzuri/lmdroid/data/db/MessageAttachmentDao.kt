package com.suzuri.lmdroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MessageAttachmentDao {
    @Insert
    suspend fun insertAll(attachments: List<MessageAttachmentEntity>)

    /** Used to build the API request history (attachments per message) and to clean up files on conversation delete. */
    @Query("SELECT * FROM message_attachments WHERE messageId IN (:messageIds) ORDER BY id ASC")
    suspend fun getForMessages(messageIds: List<Long>): List<MessageAttachmentEntity>
}
