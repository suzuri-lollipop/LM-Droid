package com.suzuri.lmdroid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user-created group for organizing conversations (e.g. "お気に入り"); a conversation belongs to at most one folder. */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long,
)
