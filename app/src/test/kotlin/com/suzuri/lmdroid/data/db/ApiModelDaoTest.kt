package com.suzuri.lmdroid.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApiModelDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var apiProfileDao: ApiProfileDao
    private lateinit var apiModelDao: ApiModelDao

    private fun newProfile(name: String) = ApiProfileEntity(
        name = name,
        providerType = ApiProfileEntity.PROVIDER_OPENAI_COMPATIBLE,
        baseUrl = "https://api.openai.com/v1",
        createdAt = 0,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        apiProfileDao = db.apiProfileDao()
        apiModelDao = db.apiModelDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `observeByProfile emits only that profile's models, ordered by modelId`() = runTest {
        val profileId = apiProfileDao.insert(newProfile("ローカルサーバー"))
        val otherProfileId = apiProfileDao.insert(newProfile("OpenAI"))
        apiModelDao.insertAll(
            listOf(
                ApiModelEntity(profileId = profileId, modelId = "llama-3"),
                ApiModelEntity(profileId = profileId, modelId = "gemma-2"),
                ApiModelEntity(profileId = otherProfileId, modelId = "gpt-4o-mini"),
            ),
        )

        apiModelDao.observeByProfile(profileId).test {
            assertEquals(listOf("gemma-2", "llama-3"), awaitItem().map { it.modelId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleteAllForProfile clears only that profile's models`() = runTest {
        val profileId = apiProfileDao.insert(newProfile("ローカルサーバー"))
        val otherProfileId = apiProfileDao.insert(newProfile("OpenAI"))
        apiModelDao.insertAll(
            listOf(
                ApiModelEntity(profileId = profileId, modelId = "llama-3"),
                ApiModelEntity(profileId = otherProfileId, modelId = "gpt-4o-mini"),
            ),
        )

        apiModelDao.deleteAllForProfile(profileId)

        apiModelDao.observeByProfile(profileId).test {
            assertEquals(emptyList<ApiModelEntity>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        apiModelDao.observeByProfile(otherProfileId).test {
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeEnabledModelOptions only returns models from enabled profiles`() = runTest {
        val enabledId = apiProfileDao.insert(newProfile("ローカルサーバー").copy(createdAt = 0))
        val disabledId = apiProfileDao.insert(newProfile("無効化済み").copy(createdAt = 1, enabled = false))
        apiModelDao.insertAll(
            listOf(
                ApiModelEntity(profileId = enabledId, modelId = "llama-3"),
                ApiModelEntity(profileId = disabledId, modelId = "gpt-4o-mini"),
            ),
        )

        apiModelDao.observeEnabledModelOptions().test {
            val options = awaitItem()
            assertEquals(listOf(enabledId), options.map { it.profileId })
            assertEquals(listOf("llama-3"), options.map { it.modelId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting a profile cascades to its models`() = runTest {
        val profileId = apiProfileDao.insert(newProfile("ローカルサーバー"))
        apiModelDao.insertAll(listOf(ApiModelEntity(profileId = profileId, modelId = "llama-3")))

        apiProfileDao.delete(profileId)

        apiModelDao.observeByProfile(profileId).test {
            assertEquals(emptyList<ApiModelEntity>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
