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
class FolderDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var folderDao: FolderDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        folderDao = db.folderDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `observeAll emits folders ordered by creation time`() = runTest {
        val olderId = folderDao.insert(FolderEntity(name = "仕事", createdAt = 100))
        val newerId = folderDao.insert(FolderEntity(name = "趣味", createdAt = 200))

        folderDao.observeAll().test {
            val folders = awaitItem()
            assertEquals(listOf(olderId, newerId), folders.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `rename changes only the name`() = runTest {
        val id = folderDao.insert(FolderEntity(name = "仕事", createdAt = 0))

        folderDao.rename(id, "プライベート")

        folderDao.observeAll().test {
            assertEquals("プライベート", awaitItem().first { it.id == id }.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `delete removes the folder`() = runTest {
        val id = folderDao.insert(FolderEntity(name = "仕事", createdAt = 0))

        folderDao.delete(id)

        folderDao.observeAll().test {
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
