package com.suzuri.lmdroid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved LLM provider configuration (e.g. a specific OpenAI-compatible endpoint) — the user can
 * register several and enable more than one at once, switching between their models from the
 * chat screen. [providerType] is a plain string (rather than an enum) since there's only one
 * provider today; see [PROVIDER_OPENAI_COMPATIBLE]. The models it offers live separately in
 * [ApiModelEntity], auto-populated from the provider's model list rather than typed in by hand.
 */
@Entity(tableName = "api_profiles")
data class ApiProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val providerType: String,
    val apiKeyCiphertext: String? = null,
    val apiKeyIv: String? = null,
    val baseUrl: String,
    val enabled: Boolean = true,
    val createdAt: Long,
) {
    companion object {
        const val PROVIDER_OPENAI_COMPATIBLE = "openai_compatible"
        // baseUrl is unused for this provider type (Brave Search has exactly one real endpoint —
        // see BraveSearchClient.DEFAULT_BASE_URL, which is what gets stored there for consistency)
        // and it never has any rows in api_models, unlike an OpenAI-compatible profile.
        const val PROVIDER_BRAVE_SEARCH = "brave_search"
    }
}
