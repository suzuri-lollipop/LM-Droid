package com.suzuri.lmdroid.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 16,
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

        // v16 added the four per-model capability columns to api_models. This one is written out
        // (rather than left to the destructive fallback) purely to spare existing installs their
        // chat history — the columns themselves get repopulated by the next "接続テスト"/save
        // anyway (see ApiProfileRepository.refreshModels).
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE api_models ADD COLUMN supportsThinking INTEGER")
                db.execSQL("ALTER TABLE api_models ADD COLUMN supportsReasoningEffort INTEGER")
                db.execSQL("ALTER TABLE api_models ADD COLUMN supportsThinkingBudget INTEGER")
                db.execSQL("ALTER TABLE api_models ADD COLUMN supportsMemory INTEGER")
            }
        }
    }
}
