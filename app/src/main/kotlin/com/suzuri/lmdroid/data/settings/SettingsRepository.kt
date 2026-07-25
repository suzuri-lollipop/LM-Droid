package com.suzuri.lmdroid.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.suzuri.lmdroid.data.db.ApiProfileDao
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Tracks which [ApiProfileEntity] (see [com.suzuri.lmdroid.data.repository.ApiProfileRepository])
 * is currently active, plus app-wide preferences unrelated to any one profile (like
 * [AppSettings.markdownEnabled]). [settings] resolves the active profile's (decrypted) credentials
 * reactively — apiKey is null whenever no profile is selected, which is exactly the "APIキー未登録"
 * state the rest of the app already knows how to show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepository(
    private val context: Context,
    private val cipher: ApiKeyCipher,
    private val apiProfileDao: ApiProfileDao,
) {
    val selectedProfileId: Flow<Long?> = context.settingsDataStore.data.map { prefs -> prefs[KEY_SELECTED_PROFILE_ID] }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_SELECTED_PROFILE_ID] to (prefs[KEY_MARKDOWN_ENABLED] ?: true) }
        .flatMapLatest { (profileId, markdownEnabled) ->
            val profileFlow = if (profileId == null) flowOf(null) else apiProfileDao.observeById(profileId)
            profileFlow.map { profile -> profile.toAppSettings(markdownEnabled) }
        }

    suspend fun currentSettings(): AppSettings = settings.first()

    suspend fun currentSelectedProfileId(): Long? = selectedProfileId.first()

    suspend fun setSelectedProfile(profileId: Long?) {
        context.settingsDataStore.edit { prefs ->
            if (profileId != null) prefs[KEY_SELECTED_PROFILE_ID] = profileId else prefs.remove(KEY_SELECTED_PROFILE_ID)
        }
    }

    suspend fun saveMarkdownEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_MARKDOWN_ENABLED] = enabled }
    }

    private fun ApiProfileEntity?.toAppSettings(markdownEnabled: Boolean): AppSettings {
        if (this == null) {
            return AppSettings(
                apiKey = null,
                model = AppSettings.DEFAULT_MODEL,
                baseUrl = AppSettings.DEFAULT_BASE_URL,
                markdownEnabled = markdownEnabled,
            )
        }
        val ciphertext = apiKeyCiphertext
        val iv = apiKeyIv
        val apiKey = if (ciphertext != null && iv != null) {
            runCatching { cipher.decrypt(ciphertext, iv) }.getOrNull()
        } else {
            null
        }
        return AppSettings(apiKey = apiKey, model = model, baseUrl = baseUrl, markdownEnabled = markdownEnabled)
    }

    private companion object {
        val KEY_SELECTED_PROFILE_ID = longPreferencesKey("selected_profile_id")
        val KEY_MARKDOWN_ENABLED = booleanPreferencesKey("markdown_enabled")
    }
}
