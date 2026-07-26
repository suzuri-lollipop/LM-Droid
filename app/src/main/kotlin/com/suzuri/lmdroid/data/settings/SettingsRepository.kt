package com.suzuri.lmdroid.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

/** A specific (profile, model) pair — identifies exactly which credentials + model to use for a given purpose. */
data class SelectedModel(val profileId: Long, val model: String)

/**
 * Resolves which (profile, model) pair is used for (a) the chat screen — adjustable there — and
 * (b) background "system" tasks (auto-titling, prompt suggestions) — adjustable from Settings →
 * システム, falling back to the chat selection when not explicitly overridden. Also tracks
 * app-wide preferences unrelated to any one profile, like [AppSettings.markdownEnabled] and the
 * Brave Search on/off toggle + its own (separately encrypted) API key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepository(
    private val context: Context,
    private val cipher: ApiKeyCipher,
    private val apiProfileDao: ApiProfileDao,
) {
    val selectedChatModel: Flow<SelectedModel?> =
        context.settingsDataStore.data.map { it.toSelectedModel(KEY_CHAT_PROFILE_ID, KEY_CHAT_MODEL) }

    val selectedSystemModel: Flow<SelectedModel?> =
        context.settingsDataStore.data.map { it.toSelectedModel(KEY_SYSTEM_PROFILE_ID, KEY_SYSTEM_MODEL) }

    private val markdownEnabledFlow: Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_MARKDOWN_ENABLED] ?: true }

    val chatSettings: Flow<AppSettings> = selectedChatModel.flatMapLatest { selected -> resolve(selected) }

    /** Falls back to the chat selection when no system-specific override has been chosen. */
    val systemSettings: Flow<AppSettings> = selectedSystemModel.flatMapLatest { systemSelected ->
        if (systemSelected != null) resolve(systemSelected) else chatSettings
    }

    suspend fun currentChatSettings(): AppSettings = chatSettings.first()

    suspend fun currentSystemSettings(): AppSettings = systemSettings.first()

    suspend fun setSelectedChatModel(profileId: Long, model: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_CHAT_PROFILE_ID] = profileId
            prefs[KEY_CHAT_MODEL] = model
        }
    }

    /** Null clears the override, falling back to the chat selection again. */
    suspend fun setSelectedSystemModel(profileId: Long?, model: String?) {
        context.settingsDataStore.edit { prefs ->
            if (profileId != null && model != null) {
                prefs[KEY_SYSTEM_PROFILE_ID] = profileId
                prefs[KEY_SYSTEM_MODEL] = model
            } else {
                prefs.remove(KEY_SYSTEM_PROFILE_ID)
                prefs.remove(KEY_SYSTEM_MODEL)
            }
        }
    }

    suspend fun saveMarkdownEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_MARKDOWN_ENABLED] = enabled }
    }

    /** Whether the Brave Search harness (see ConversationRepository) is forced on for every message. */
    val braveSearchEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_BRAVE_SEARCH_ENABLED] ?: false }

    suspend fun currentBraveSearchEnabled(): Boolean = braveSearchEnabled.first()

    suspend fun setBraveSearchEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_BRAVE_SEARCH_ENABLED] = enabled }
    }

    /** Blank clears the stored key. */
    suspend fun setBraveSearchApiKey(apiKey: String) {
        context.settingsDataStore.edit { prefs ->
            if (apiKey.isBlank()) {
                prefs.remove(KEY_BRAVE_API_KEY_CIPHERTEXT)
                prefs.remove(KEY_BRAVE_API_KEY_IV)
            } else {
                val encrypted = cipher.encrypt(apiKey)
                prefs[KEY_BRAVE_API_KEY_CIPHERTEXT] = encrypted.ciphertextBase64
                prefs[KEY_BRAVE_API_KEY_IV] = encrypted.ivBase64
            }
        }
    }

    /** Decrypted Brave Search API key, or null if none is saved (or decryption fails). */
    suspend fun currentBraveSearchApiKey(): String? {
        val prefs = context.settingsDataStore.data.first()
        val ciphertext = prefs[KEY_BRAVE_API_KEY_CIPHERTEXT]
        val iv = prefs[KEY_BRAVE_API_KEY_IV]
        if (ciphertext == null || iv == null) return null
        return runCatching { cipher.decrypt(ciphertext, iv) }.getOrNull()
    }

    /** How many web_search tool round-trips one reply may make before being forced to answer with what it has. 0 (the default) means unconditionally allowed, up to ConversationRepository's own hard safety ceiling. */
    val webSearchMaxToolRounds: Flow<Int> =
        context.settingsDataStore.data.map { it[KEY_WEB_SEARCH_MAX_TOOL_ROUNDS] ?: 0 }

    suspend fun currentWebSearchMaxToolRounds(): Int = webSearchMaxToolRounds.first()

    suspend fun setWebSearchMaxToolRounds(rounds: Int) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_WEB_SEARCH_MAX_TOOL_ROUNDS] = rounds.coerceAtLeast(0) }
    }

    /** A user-authored instruction prepended as a leading "system" message on every request, not tied to any one conversation. Blank means none is sent. */
    val systemPrompt: Flow<String> = context.settingsDataStore.data.map { it[KEY_SYSTEM_PROMPT] ?: "" }

    suspend fun currentSystemPrompt(): String = systemPrompt.first()

    suspend fun setSystemPrompt(prompt: String) {
        context.settingsDataStore.edit { prefs ->
            if (prompt.isBlank()) prefs.remove(KEY_SYSTEM_PROMPT) else prefs[KEY_SYSTEM_PROMPT] = prompt
        }
    }

    private fun resolve(selected: SelectedModel?): Flow<AppSettings> {
        if (selected == null) {
            return markdownEnabledFlow.map { markdownEnabled ->
                AppSettings(
                    apiKey = null,
                    model = AppSettings.DEFAULT_MODEL,
                    baseUrl = AppSettings.DEFAULT_BASE_URL,
                    markdownEnabled = markdownEnabled,
                )
            }
        }
        return apiProfileDao.observeById(selected.profileId).flatMapLatest { profile ->
            markdownEnabledFlow.map { markdownEnabled -> profile.toAppSettings(selected.model, markdownEnabled) }
        }
    }

    private fun Preferences.toSelectedModel(
        profileKey: Preferences.Key<Long>,
        modelKey: Preferences.Key<String>,
    ): SelectedModel? {
        val profileId = this[profileKey] ?: return null
        val model = this[modelKey] ?: return null
        return SelectedModel(profileId, model)
    }

    private fun ApiProfileEntity?.toAppSettings(model: String, markdownEnabled: Boolean): AppSettings {
        if (this == null) {
            return AppSettings(apiKey = null, model = model, baseUrl = AppSettings.DEFAULT_BASE_URL, markdownEnabled = markdownEnabled)
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
        val KEY_CHAT_PROFILE_ID = longPreferencesKey("chat_profile_id")
        val KEY_CHAT_MODEL = stringPreferencesKey("chat_model")
        val KEY_SYSTEM_PROFILE_ID = longPreferencesKey("system_profile_id")
        val KEY_SYSTEM_MODEL = stringPreferencesKey("system_model")
        val KEY_MARKDOWN_ENABLED = booleanPreferencesKey("markdown_enabled")
        val KEY_BRAVE_SEARCH_ENABLED = booleanPreferencesKey("brave_search_enabled")
        val KEY_BRAVE_API_KEY_CIPHERTEXT = stringPreferencesKey("brave_search_api_key_ciphertext")
        val KEY_BRAVE_API_KEY_IV = stringPreferencesKey("brave_search_api_key_iv")
        val KEY_WEB_SEARCH_MAX_TOOL_ROUNDS = intPreferencesKey("web_search_max_tool_rounds")
        val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
    }
}
