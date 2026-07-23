package com.suzuri.lmdroid.data.settings

import android.content.Context
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

    suspend fun saveModel(model: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_MODEL] = model
        }
    }

    private companion object {
        val KEY_API_KEY_CIPHERTEXT = stringPreferencesKey("api_key_ciphertext")
        val KEY_API_KEY_IV = stringPreferencesKey("api_key_iv")
        val KEY_MODEL = stringPreferencesKey("model_name")
    }
}
