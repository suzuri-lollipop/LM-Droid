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
    private lateinit var folderDao: FolderDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        conversationDao = db.conversationDao()
        messageDao = db.messageDao()
        folderDao = db.folderDao()
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
    fun `observeConversation emits updates for only the requested conversation`() = runTest {
        val id = conversationDao.insert(ConversationEntity(title = "新しい会話", createdAt = 0, updatedAt = 0))
        val otherId = conversationDao.insert(ConversationEntity(title = "other", createdAt = 0, updatedAt = 0))

        conversationDao.observeConversation(id).test {
            assertEquals("新しい会話", awaitItem()?.title)

            conversationDao.updateTitle(id, "旅行の計画について")
            assertEquals("旅行の計画について", awaitItem()?.title)

            // A change to a different conversation must not emit here.
            conversationDao.updateTitle(otherId, "unrelated change")
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getRecent returns the most recently updated conversations up to the limit`() = runTest {
        val oldest = conversationDao.insert(ConversationEntity(title = "oldest", createdAt = 100, updatedAt = 100))
        val middle = conversationDao.insert(ConversationEntity(title = "middle", createdAt = 200, updatedAt = 200))
        val newest = conversationDao.insert(ConversationEntity(title = "newest", createdAt = 300, updatedAt = 300))

        val recent = conversationDao.getRecent(limit = 2)

        assertEquals(listOf(newest, middle), recent.map { it.id })
        assertTrue(recent.none { it.id == oldest })
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

    @Test
    fun `observeByFolder emits only conversations assigned to that folder`() = runTest {
        val folderId = folderDao.insert(FolderEntity(name = "お気に入り", createdAt = 0))
        val inFolder = conversationDao.insert(ConversationEntity(title = "in folder", createdAt = 100, updatedAt = 100))
        conversationDao.insert(ConversationEntity(title = "not in folder", createdAt = 200, updatedAt = 200))
        conversationDao.setFolder(inFolder, folderId)

        conversationDao.observeByFolder(folderId).test {
            val conversations = awaitItem()
            assertEquals(listOf(inFolder), conversations.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting a folder unfiles its conversations instead of deleting them`() = runTest {
        val folderId = folderDao.insert(FolderEntity(name = "お気に入り", createdAt = 0))
        val id = conversationDao.insert(ConversationEntity(title = "t", createdAt = 0, updatedAt = 0))
        conversationDao.setFolder(id, folderId)

        folderDao.delete(folderId)

        val remaining = conversationDao.getRecent(limit = 10)
        assertEquals(1, remaining.size)
        assertEquals(null, remaining[0].folderId)
    }
}
