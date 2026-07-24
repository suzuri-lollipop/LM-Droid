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
class ConversationDaoTest {

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
    fun `observeAll emits conversations ordered by most recently updated first`() = runTest {
        val olderId = conversationDao.insert(ConversationEntity(title = "older", createdAt = 100, updatedAt = 100))
        val newerId = conversationDao.insert(ConversationEntity(title = "newer", createdAt = 200, updatedAt = 200))

        conversationDao.observeAll().test {
            val conversations = awaitItem()
            assertEquals(listOf(newerId, olderId), conversations.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `touch moves a conversation to the front`() = runTest {
        val firstId = conversationDao.insert(ConversationEntity(title = "first", createdAt = 100, updatedAt = 100))
        val secondId = conversationDao.insert(ConversationEntity(title = "second", createdAt = 200, updatedAt = 200))

        conversationDao.touch(firstId, updatedAt = 300)

        conversationDao.observeAll().test {
            val conversations = awaitItem()
            assertEquals(listOf(firstId, secondId), conversations.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateTitle changes only the title`() = runTest {
        val id = conversationDao.insert(ConversationEntity(title = "新しい会話", createdAt = 0, updatedAt = 0))

        conversationDao.updateTitle(id, "こんにちは")

        val updated = conversationDao.getMostRecent()
        assertEquals("こんにちは", updated?.title)
    }

    @Test
    fun `deleting a conversation cascades to its messages`() = runTest {
        val id = conversationDao.insert(ConversationEntity(title = "t", createdAt = 0, updatedAt = 0))
        messageDao.insert(MessageEntity(conversationId = id, role = MessageRole.USER, content = "hi", createdAt = 0))

        conversationDao.delete(id)

        assertTrue(messageDao.getMessages(id).isEmpty())
    }
}
