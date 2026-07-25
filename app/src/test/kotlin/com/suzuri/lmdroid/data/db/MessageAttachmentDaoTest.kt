package com.suzuri.lmdroid.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageAttachmentDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var conversationDao: ConversationDao
    private lateinit var messageDao: MessageDao
    private lateinit var messageAttachmentDao: MessageAttachmentDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        conversationDao = db.conversationDao()
        messageDao = db.messageDao()
        messageAttachmentDao = db.messageAttachmentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `getForMessages returns attachments only for the requested messages`() = runTest {
        val conversationId = conversationDao.insert(ConversationEntity(title = "t", createdAt = 0, updatedAt = 0))
        val messageId = messageDao.insert(
            MessageEntity(conversationId = conversationId, role = MessageRole.USER, content = "look at this", createdAt = 100),
        )
        val otherMessageId = messageDao.insert(
            MessageEntity(conversationId = conversationId, role = MessageRole.USER, content = "unrelated", createdAt = 200),
        )
        messageAttachmentDao.insertAll(
            listOf(
                MessageAttachmentEntity(messageId = messageId, filePath = "/a.jpg", mimeType = "image/jpeg", createdAt = 100),
                MessageAttachmentEntity(messageId = otherMessageId, filePath = "/b.jpg", mimeType = "image/jpeg", createdAt = 200),
            ),
        )

        val attachments = messageAttachmentDao.getForMessages(listOf(messageId))

        assertEquals(1, attachments.size)
        assertEquals("/a.jpg", attachments[0].filePath)
    }

    @Test
    fun `observeMessagesWithAttachments joins each message with only its own attachments`() = runTest {
        val conversationId = conversationDao.insert(ConversationEntity(title = "t", createdAt = 0, updatedAt = 0))
        val messageId = messageDao.insert(
            MessageEntity(conversationId = conversationId, role = MessageRole.USER, content = "look at this", createdAt = 100),
        )
        messageDao.insert(
            MessageEntity(conversationId = conversationId, role = MessageRole.ASSISTANT, content = "nice photo", createdAt = 200),
        )
        messageAttachmentDao.insertAll(
            listOf(MessageAttachmentEntity(messageId = messageId, filePath = "/a.jpg", mimeType = "image/jpeg", createdAt = 100)),
        )

        messageDao.observeMessagesWithAttachments(conversationId).test {
            val messages = awaitItem()
            assertEquals(2, messages.size)
            assertEquals(listOf("/a.jpg"), messages[0].attachments.map { it.filePath })
            assertTrue(messages[1].attachments.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting a message cascades to its attachments`() = runTest {
        val conversationId = conversationDao.insert(ConversationEntity(title = "t", createdAt = 0, updatedAt = 0))
        val messageId = messageDao.insert(
            MessageEntity(conversationId = conversationId, role = MessageRole.USER, content = "look at this", createdAt = 100),
        )
        messageAttachmentDao.insertAll(
            listOf(MessageAttachmentEntity(messageId = messageId, filePath = "/a.jpg", mimeType = "image/jpeg", createdAt = 100)),
        )

        messageDao.deleteMessage(messageId)

        assertEquals(emptyList<MessageAttachmentEntity>(), messageAttachmentDao.getForMessages(listOf(messageId)))
    }
}
