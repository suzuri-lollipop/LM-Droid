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
class SkillDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var skillDao: SkillDao

    private fun newSkill(name: String) = SkillEntity(
        name = name,
        description = "",
        content = "",
        createdAt = 0,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        skillDao = db.skillDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `observeAll emits skills ordered by creation time`() = runTest {
        val olderId = skillDao.insert(newSkill("翻訳").copy(createdAt = 100))
        val newerId = skillDao.insert(newSkill("要約").copy(createdAt = 200))

        skillDao.observeAll().test {
            val skills = awaitItem()
            assertEquals(listOf(olderId, newerId), skills.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `update overwrites the stored fields`() = runTest {
        val id = skillDao.insert(newSkill("翻訳"))
        val existing = skillDao.getById(id)!!

        skillDao.update(existing.copy(name = "更新後の名前", description = "説明", content = "常に日本語に翻訳してください"))

        val updated = skillDao.getById(id)
        assertEquals("更新後の名前", updated?.name)
        assertEquals("説明", updated?.description)
        assertEquals("常に日本語に翻訳してください", updated?.content)
    }

    @Test
    fun `delete removes the skill`() = runTest {
        val id = skillDao.insert(newSkill("翻訳"))

        skillDao.delete(id)

        assertNull(skillDao.getById(id))
    }
}
