package com.suzuri.lmdroid.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SystemPromptDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var systemPromptDao: SystemPromptDao

    private fun newPrompt(name: String) = SystemPromptEntity(
        name = name,
        content = "",
        createdAt = 0,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        systemPromptDao = db.systemPromptDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `observeAll emits prompts ordered by creation time`() = runTest {
        val olderId = systemPromptDao.insert(newPrompt("敬語").copy(createdAt = 100))
        val newerId = systemPromptDao.insert(newPrompt("カジュアル").copy(createdAt = 200))

        systemPromptDao.observeAll().test {
            val prompts = awaitItem()
            assertEquals(listOf(olderId, newerId), prompts.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `update overwrites the stored fields`() = runTest {
        val id = systemPromptDao.insert(newPrompt("敬語"))
        val existing = systemPromptDao.getById(id)!!

        systemPromptDao.update(existing.copy(name = "更新後の名前", content = "常に日本語の敬語で回答してください"))

        val updated = systemPromptDao.getById(id)
        assertEquals("更新後の名前", updated?.name)
        assertEquals("常に日本語の敬語で回答してください", updated?.content)
    }

    @Test
    fun `delete removes the prompt`() = runTest {
        val id = systemPromptDao.insert(newPrompt("敬語"))

        systemPromptDao.delete(id)

        assertNull(systemPromptDao.getById(id))
    }

    @Test
    fun `observeById emits updates for only the requested prompt`() = runTest {
        val id = systemPromptDao.insert(newPrompt("敬語"))
        val otherId = systemPromptDao.insert(newPrompt("カジュアル"))

        systemPromptDao.observeById(id).test {
            assertEquals("敬語", awaitItem()?.name)

            systemPromptDao.update(systemPromptDao.getById(id)!!.copy(name = "改名後"))
            assertEquals("改名後", awaitItem()?.name)

            // A change to a different prompt must not change the value observed here. Room's
            // invalidation tracking is table-scoped, though, so the flow may still re-emit our
            // own unchanged row when the other one is updated — expectNoEvents() raced that
            // delivery and flaked. Instead, trigger one more of our own updates and skip any
            // stale duplicates on the way to it.
            systemPromptDao.update(systemPromptDao.getById(otherId)!!.copy(name = "unrelated"))
            systemPromptDao.update(systemPromptDao.getById(id)!!.copy(name = "最終確認"))
            var item = awaitItem()
            while (item?.name == "改名後") {
                item = awaitItem()
            }
            assertEquals("最終確認", item?.name)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
