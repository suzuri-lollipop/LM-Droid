package com.suzuri.lmdroid.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        FolderEntity::class,
        ApiProfileEntity::class,
        ApiModelEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun folderDao(): FolderDao
    abstract fun apiProfileDao(): ApiProfileDao
    abstract fun apiModelDao(): ApiModelDao

    companion object {
        const val DATABASE_NAME = "lmdroid.db"
    }
}
