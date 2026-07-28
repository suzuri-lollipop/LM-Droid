package com.suzuri.lmdroid.data.repository

import com.suzuri.lmdroid.data.db.ApiModelDao
import com.suzuri.lmdroid.data.db.ApiModelEntity
import com.suzuri.lmdroid.data.db.ApiProfileDao
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.db.ModelOptionRow
import com.suzuri.lmdroid.data.network.OpenAiApiClient
import com.suzuri.lmdroid.data.settings.ApiKeyCipher
import com.suzuri.lmdroid.data.settings.AppSettings
import com.suzuri.lmdroid.data.tts.VoicevoxCompatibleClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Manages saved LLM provider configurations ("profiles") — the user can register several
 * OpenAI-compatible endpoints (e.g. a local llama.cpp server and a hosted OpenAI key), enable
 * more than one at once, and switch between their models from the chat screen. Each profile's API
 * key is encrypted at rest via [ApiKeyCipher]. Models are never typed in by hand — [refreshModels]
 * fetches them from the provider's `/models` endpoint.
 */
class ApiProfileRepository(
    private val apiProfileDao: ApiProfileDao,
    private val apiModelDao: ApiModelDao,
    private val cipher: ApiKeyCipher,
    private val openAiApiClient: OpenAiApiClient,
) {
    fun observeProfiles(): Flow<List<ApiProfileEntity>> = apiProfileDao.observeAll()

    suspend fun getProfile(id: Long): ApiProfileEntity? = apiProfileDao.getById(id)

    fun observeModels(profileId: Long): Flow<List<String>> =
        apiModelDao.observeByProfile(profileId).map { models -> models.map { it.modelId } }

    /** Every model offered by every *enabled* profile — the data behind the chat/system model pickers. */
    fun observeEnabledModelOptions(): Flow<List<ModelOptionRow>> = apiModelDao.observeEnabledModelOptions()

    suspend fun createProfile(name: String, providerType: String): Long {
        return apiProfileDao.insert(
            ApiProfileEntity(
                name = name.ifBlank { "新しいプロファイル" },
                providerType = providerType,
                baseUrl = if (providerType == ApiProfileEntity.PROVIDER_VOICEVOX_COMPATIBLE) {
                    VoicevoxCompatibleClient.DEFAULT_BASE_URL
                } else {
                    AppSettings.DEFAULT_BASE_URL
                },
                createdAt = System.currentTimeMillis(),
                voicevoxSpeakerId = if (providerType == ApiProfileEntity.PROVIDER_VOICEVOX_COMPATIBLE) {
                    ApiProfileEntity.DEFAULT_VOICEVOX_SPEAKER_ID
                } else {
                    null
                },
            ),
        )
    }

    /** Blank [apiKey] clears the profile's stored key, matching the old single-profile "clear the field to forget the key" behavior. */
    suspend fun updateProfile(id: Long, name: String, apiKey: String, baseUrl: String) {
        val existing = apiProfileDao.getById(id) ?: return
        val encrypted = apiKey.takeIf { it.isNotBlank() }?.let { cipher.encrypt(it) }
        apiProfileDao.update(
            existing.copy(
                name = name.ifBlank { existing.name },
                apiKeyCiphertext = encrypted?.ciphertextBase64,
                apiKeyIv = encrypted?.ivBase64,
                baseUrl = baseUrl.ifBlank { AppSettings.DEFAULT_BASE_URL },
            ),
        )
    }

    /** Same as [updateProfile] but for a PROVIDER_VOICEVOX_COMPATIBLE profile — a local, unauthenticated server, so there's a speaker id to persist and no API key. */
    suspend fun updateVoicevoxProfile(id: Long, name: String, baseUrl: String, speakerId: Int) {
        val existing = apiProfileDao.getById(id) ?: return
        apiProfileDao.update(
            existing.copy(
                name = name.ifBlank { existing.name },
                baseUrl = baseUrl.ifBlank { existing.baseUrl },
                voicevoxSpeakerId = speakerId,
            ),
        )
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        apiProfileDao.setEnabled(id, enabled)
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

    /** Replaces the profile's stored model list with whatever the provider's `/models` endpoint currently reports. */
    suspend fun refreshModels(profileId: Long, apiKey: String, baseUrl: String): Result<Int> {
        return openAiApiClient.listModels(apiKey, baseUrl).map { models ->
            apiModelDao.deleteAllForProfile(profileId)
            if (models.isNotEmpty()) {
                apiModelDao.insertAll(models.map { modelId -> ApiModelEntity(profileId = profileId, modelId = modelId) })
            }
            models.size
        }
    }
}
