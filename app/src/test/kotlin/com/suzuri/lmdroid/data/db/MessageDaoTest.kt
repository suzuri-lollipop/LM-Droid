package com.suzuri.lmdroid.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var conversationDao: ConversationDao
    private lateinit var messageDao: MessageDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        conversationDao = db.conversationDao()
        messageDao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `messages are returned in chronological order`() = runTest {
        val conversationId = conversationDao.insert(ConversationEntity(title = "t", createdAt = 0, updatedAt = 0))
        messageDao.insert(
            MessageEntity(conversationId = conversationId, role = MessageRole.USER, content = "hi", createdAt = 100),
        )
        messageDao.insert(
            MessageEntity(conversationId = conversationId, role = MessageRole.ASSISTANT, content = "", createdAt = 200),
        )

        val messages = messageDao.getMessages(conversationId)

        assertEquals(2, messages.size)
        assertEquals("hi", messages[0].content)
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
    }

    @Test
    fun `updateContent overwrites the placeholder row in place`() = runTest {
        val conversationId = conversationDao.insert(ConversationEntity(title = "t", createdAt = 0, updatedAt = 0))
        val placeholderId = messageDao.insert(
            MessageEntity(conversationId = conversationId, role = MessageRole.ASSISTANT, content = "", createdAt = 100),
        )

        messageDao.updateContent(placeholderId, "streamed answer")

        val messages = messageDao.getMessages(conversationId)
        assertEquals(1, messages.size)
        assertEquals("streamed answer", messages[0].content)
        assertFalse(messages[0].isError)
    }

    @Test
    fun `observeMessages emits a new list when a row is inserted`() = runTest {
        val conversationId = conversationDao.insert(ConversationEntity(title = "t", createdAt = 0, updatedAt = 0))
        messageDao.insert(
            MessageEntity(conversationId = conversationId, role = MessageRole.USER, content = "hi", createdAt = 100),
        )

        messageDao.observeMessages(conversationId).test {
            assertEquals(1, awaitItem().size)

            messageDao.insert(
                MessageEntity(conversationId = conversationId, role = MessageRole.ASSISTANT, content = "yo", createdAt = 200),
            )

            assertEquals(2, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
