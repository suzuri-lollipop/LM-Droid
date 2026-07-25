package com.suzuri.lmdroid.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApiProfileDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var apiProfileDao: ApiProfileDao

    private fun newProfile(name: String) = ApiProfileEntity(
        name = name,
        providerType = ApiProfileEntity.PROVIDER_OPENAI_COMPATIBLE,
        model = "gpt-4o-mini",
        baseUrl = "https://api.openai.com/v1",
        createdAt = 0,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        apiProfileDao = db.apiProfileDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `observeAll emits profiles ordered by creation time`() = runTest {
        val olderId = apiProfileDao.insert(newProfile("ローカルサーバー").copy(createdAt = 100))
        val newerId = apiProfileDao.insert(newProfile("OpenAI").copy(createdAt = 200))

        apiProfileDao.observeAll().test {
            val profiles = awaitItem()
            assertEquals(listOf(olderId, newerId), profiles.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `update overwrites the stored fields`() = runTest {
        val id = apiProfileDao.insert(newProfile("ローカルサーバー"))
        val existing = apiProfileDao.getById(id)!!

        apiProfileDao.update(
            existing.copy(
                name = "更新後の名前",
                apiKeyCiphertext = "cipher",
                apiKeyIv = "iv",
                model = "llama-3",
                baseUrl = "http://localhost:8080/v1",
            ),
        )

        val updated = apiProfileDao.getById(id)
        assertEquals("更新後の名前", updated?.name)
        assertEquals("cipher", updated?.apiKeyCiphertext)
        assertEquals("llama-3", updated?.model)
    }

    @Test
    fun `delete removes the profile`() = runTest {
        val id = apiProfileDao.insert(newProfile("ローカルサーバー"))

        apiProfileDao.delete(id)

        assertNull(apiProfileDao.getById(id))
    }

    @Test
    fun `observeById emits updates for only the requested profile`() = runTest {
        val id = apiProfileDao.insert(newProfile("ローカルサーバー"))
        val otherId = apiProfileDao.insert(newProfile("OpenAI"))

        apiProfileDao.observeById(id).test {
            assertEquals("ローカルサーバー", awaitItem()?.name)

            apiProfileDao.update(apiProfileDao.getById(id)!!.copy(name = "改名後"))
            assertEquals("改名後", awaitItem()?.name)

            // A change to a different profile must not emit here.
            apiProfileDao.update(apiProfileDao.getById(otherId)!!.copy(name = "unrelated"))
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }
}
