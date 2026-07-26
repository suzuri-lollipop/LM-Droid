package com.suzuri.lmdroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SystemPromptDao {
    @Insert
    suspend fun insert(prompt: SystemPromptEntity): Long

    @Update
    suspend fun update(prompt: SystemPromptEntity)

    @Query("SELECT * FROM system_prompts ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<SystemPromptEntity>>

    @Query("SELECT * FROM system_prompts WHERE id = :id")
    suspend fun getById(id: Long): SystemPromptEntity?

    @Query("SELECT * FROM system_prompts WHERE id = :id")
    fun observeById(id: Long): Flow<SystemPromptEntity?>

    @Query("DELETE FROM system_prompts WHERE id = :id")
    suspend fun delete(id: Long)
}
