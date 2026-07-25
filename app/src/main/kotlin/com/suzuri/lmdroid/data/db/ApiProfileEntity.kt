package com.suzuri.lmdroid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved LLM provider configuration (e.g. a specific OpenAI-compatible endpoint) — the user can
 * register several and switch between them from Settings. [providerType] is a plain string
 * (rather than an enum) since there's only one provider today; see [PROVIDER_OPENAI_COMPATIBLE].
 */
@Entity(tableName = "api_profiles")
data class ApiProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val providerType: String,
    val apiKeyCiphertext: String? = null,
    val apiKeyIv: String? = null,
    val model: String,
    val baseUrl: String,
    val createdAt: Long,
) {
    companion object {
        const val PROVIDER_OPENAI_COMPATIBLE = "openai_compatible"
    }
}
