package com.suzuri.lmdroid.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsRepository(
    private val context: Context,
    private val cipher: ApiKeyCipher,
) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val ciphertext = prefs[KEY_API_KEY_CIPHERTEXT]
        val iv = prefs[KEY_API_KEY_IV]
        val apiKey = if (ciphertext != null && iv != null) {
            runCatching { cipher.decrypt(ciphertext, iv) }.getOrNull()
        } else {
            null
        }
        AppSettings(
            apiKey = apiKey,
            model = prefs[KEY_MODEL] ?: AppSettings.DEFAULT_MODEL,
            baseUrl = prefs[KEY_BASE_URL] ?: AppSettings.DEFAULT_BASE_URL,
            markdownEnabled = prefs[KEY_MARKDOWN_ENABLED] ?: true,
        )
    }

    suspend fun currentSettings(): AppSettings = settings.first()

    suspend fun saveApiKey(rawApiKey: String) {
        val encrypted = cipher.encrypt(rawApiKey)
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_API_KEY_CIPHERTEXT] = encrypted.ciphertextBase64
            prefs[KEY_API_KEY_IV] = encrypted.ivBase64
        }
    }

    suspend fun clearApiKey() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(KEY_API_KEY_CIPHERTEXT)
            prefs.remove(KEY_API_KEY_IV)
        }
    }

    suspend fun saveModel(model: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_MODEL] = model
        }
    }

    suspend fun saveBaseUrl(baseUrl: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = baseUrl
        }
    }

    suspend fun saveMarkdownEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_MARKDOWN_ENABLED] = enabled
        }
    }

    private companion object {
        val KEY_API_KEY_CIPHERTEXT = stringPreferencesKey("api_key_ciphertext")
        val KEY_API_KEY_IV = stringPreferencesKey("api_key_iv")
        val KEY_MODEL = stringPreferencesKey("model_name")
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_MARKDOWN_ENABLED = booleanPreferencesKey("markdown_enabled")
    }
}
