package com.suzuri.lmdroid.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
    val isError: Boolean = false,
    // The reply's "thinking process" — chain-of-thought reasoning and tool activity (web search /
    // page fetches), interleaved in the order they actually happened — a JSON-encoded
    // List<ThinkingTimelineEntry>, shown in a collapsible section. Null for messages that never
    // had one (e.g. the user's own messages).
    val thinkingTimelineJson: String? = null,
)
