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
        MessageAttachmentEntity::class,
        SystemPromptEntity::class,
        SkillEntity::class,
    ],
    version = 13,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun folderDao(): FolderDao
    abstract fun apiProfileDao(): ApiProfileDao
    abstract fun apiModelDao(): ApiModelDao
    abstract fun messageAttachmentDao(): MessageAttachmentDao
    abstract fun systemPromptDao(): SystemPromptDao
    abstract fun skillDao(): SkillDao

    companion object {
        const val DATABASE_NAME = "lmdroid.db"
    }
}
