package com.suzuri.lmdroid.data.repository

import com.suzuri.lmdroid.data.db.ApiProfileDao
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.settings.ApiKeyCipher
import com.suzuri.lmdroid.data.settings.AppSettings
import kotlinx.coroutines.flow.Flow

/**
 * Manages saved LLM provider configurations ("profiles") — the user can register several
 * OpenAI-compatible endpoints (e.g. a local llama.cpp server and a hosted OpenAI key) and switch
 * between them from Settings. Each profile's API key is encrypted at rest via [ApiKeyCipher], the
 * same as the single global key used to be before profiles existed.
 */
class ApiProfileRepository(
    private val apiProfileDao: ApiProfileDao,
    private val cipher: ApiKeyCipher,
) {
    fun observeProfiles(): Flow<List<ApiProfileEntity>> = apiProfileDao.observeAll()

    suspend fun getProfile(id: Long): ApiProfileEntity? = apiProfileDao.getById(id)

    suspend fun createProfile(name: String, providerType: String): Long {
        return apiProfileDao.insert(
            ApiProfileEntity(
                name = name.ifBlank { "新しいプロファイル" },
                providerType = providerType,
                model = AppSettings.DEFAULT_MODEL,
                baseUrl = AppSettings.DEFAULT_BASE_URL,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Blank [apiKey] clears the profile's stored key, matching the old single-profile "clear the field to forget the key" behavior. */
    suspend fun updateProfile(id: Long, name: String, apiKey: String, model: String, baseUrl: String) {
        val existing = apiProfileDao.getById(id) ?: return
        val encrypted = apiKey.takeIf { it.isNotBlank() }?.let { cipher.encrypt(it) }
        apiProfileDao.update(
            existing.copy(
                name = name.ifBlank { existing.name },
                apiKeyCiphertext = encrypted?.ciphertextBase64,
                apiKeyIv = encrypted?.ivBase64,
                model = model.ifBlank { AppSettings.DEFAULT_MODEL },
                baseUrl = baseUrl.ifBlank { AppSettings.DEFAULT_BASE_URL },
            ),
        )
    }

    suspend fun deleteProfile(id: Long) {
        apiProfileDao.delete(id)
    }

    suspend fun decryptApiKey(profile: ApiProfileEntity): String? {
        val ciphertext = profile.apiKeyCiphertext
        val iv = profile.apiKeyIv
        if (ciphertext == null || iv == null) return null
        return runCatching { cipher.decrypt(ciphertext, iv) }.getOrNull()
    }
}
