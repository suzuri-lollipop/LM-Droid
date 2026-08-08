package com.suzuri.lmdroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiProfileDao {
    @Insert
    suspend fun insert(profile: ApiProfileEntity): Long

    @Update
    suspend fun update(profile: ApiProfileEntity)

    @Query("SELECT * FROM api_profiles ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ApiProfileEntity>>

    @Query("SELECT * FROM api_profiles ORDER BY createdAt ASC")
    suspend fun getAll(): List<ApiProfileEntity>

    @Query("SELECT * FROM api_profiles WHERE id = :id")
    suspend fun getById(id: Long): ApiProfileEntity?

    @Query("SELECT * FROM api_profiles WHERE id = :id")
    fun observeById(id: Long): Flow<ApiProfileEntity?>

    @Query("UPDATE api_profiles SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM api_profiles WHERE id = :id")
    suspend fun delete(id: Long)
}
