package com.suzuri.lmdroid.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An image attached to a user message (assistant/system messages never have attachments). The
 * file itself lives in app-private storage (see AttachmentFileStore) rather than referencing the
 * picker's original content:// URI, whose read grant isn't guaranteed to survive an app restart.
 */
@Entity(
    tableName = "message_attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class MessageAttachmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val messageId: Long,
    val filePath: String,
    val mimeType: String,
    val createdAt: Long,
)
