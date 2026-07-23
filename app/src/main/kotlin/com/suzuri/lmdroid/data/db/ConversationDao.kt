package com.suzuri.lmdroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getMostRecent(): ConversationEntity?

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)
}
