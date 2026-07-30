package com.suzuri.lmdroid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved provider configuration — not just LLM endpoints: also Brave Search (web_search tool)
 * and VOICEVOX-compatible speech synthesis, all sharing one list/table since they all boil down
 * to "a name, optionally a key, optionally a base URL" and the same enable/select UX. The user can
 * register several and enable more than one at once, switching between an LLM profile's models
 * from the chat screen. [providerType] (see the PROVIDER_* constants) decides which fields
 * actually apply and which edit screen handles it. An OpenAI-compatible profile's models live
 * separately in [ApiModelEntity], auto-populated from the provider's model list rather than typed
 * in by hand — the other provider types never have rows there.
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
    // Only meaningful for PROVIDER_VOICEVOX_COMPATIBLE — which character/style to synthesize
    // with, e.g. 3 for ずんだもん(ノーマル). VOICEVOX/AivisSpeech speaker ids are stable, well-known
    // values users already know from running the engine itself, so this is a plain manually-typed
    // number rather than a fetched-and-selected list like OpenAI-compatible's models.
    val voicevoxSpeakerId: Int? = null,
) {
    companion object {
        const val PROVIDER_OPENAI_COMPATIBLE = "openai_compatible"
        // baseUrl is unused for this provider type (Brave Search has exactly one real endpoint —
        // see BraveSearchClient.DEFAULT_BASE_URL, which is what gets stored there for consistency)
        // and it never has any rows in api_models, unlike an OpenAI-compatible profile.
        const val PROVIDER_BRAVE_SEARCH = "brave_search"
        // A local VOICEVOX or AivisSpeech engine (AivisSpeech's HTTP API is intentionally
        // VOICEVOX-compatible) — baseUrl points at wherever the user is running it (typically
        // http://127.0.0.1:50021 for VOICEVOX or :10101 for AivisSpeech). No API key: these are
        // unauthenticated local servers, so apiKeyCiphertext/apiKeyIv stay null.
        const val PROVIDER_VOICEVOX_COMPATIBLE = "voicevox_compatible"
        // The official YouTube Data API v3 (just an API key, no OAuth) — used only to resolve the
        // "play_music" tool's free-text query to a specific video id (see YouTubeDataApiClient,
        // DeviceMusicController.prepareOpenYoutubeMusicTrack), since YouTube Music's own
        // ACTION_MEDIA_PLAY_FROM_SEARCH handling only populates search results without actually
        // starting playback. baseUrl is unused, same reasoning as PROVIDER_BRAVE_SEARCH.
        const val PROVIDER_YOUTUBE_DATA_API = "youtube_data_api"

        // VOICEVOX's own default speaker (四国めたん, ノーマル) — prefilled when creating a new
        // VOICEVOX-compatible profile, and used as a defensive fallback if one is ever missing
        // (e.g. an old import from before this field existed).
        const val DEFAULT_VOICEVOX_SPEAKER_ID = 2
    }
}
