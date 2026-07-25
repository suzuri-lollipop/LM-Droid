package com.suzuri.lmdroid.data.db

import androidx.room.Embedded
import androidx.room.Relation

/** A message joined with whatever images (if any) were attached to it — see [MessageDao]. */
data class MessageWithAttachments(
    @Embedded val message: MessageEntity,
    @Relation(parentColumn = "id", entityColumn = "messageId")
    val attachments: List<MessageAttachmentEntity>,
)
