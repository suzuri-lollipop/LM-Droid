package com.suzuri.lmdroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SkillDao {
    @Insert
    suspend fun insert(skill: SkillEntity): Long

    @Update
    suspend fun update(skill: SkillEntity)

    @Query("SELECT * FROM skills ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE id = :id")
    suspend fun getById(id: Long): SkillEntity?

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun delete(id: Long)
}
