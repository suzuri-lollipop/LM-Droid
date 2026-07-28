package com.suzuri.lmdroid.data.settings

data class AppSettings(
    val apiKey: String?,
    val model: String,
    val baseUrl: String,
    val markdownEnabled: Boolean = true,
    // The registered ApiProfileEntity's own display name (e.g. "ローカルサーバー") — null when no
    // profile backs this AppSettings at all (nothing configured yet). Lets a UI show which profile
    // is actually active (see AssistScreen's title) without having to look the profile back up.
    val profileName: String? = null,
) {
    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}
