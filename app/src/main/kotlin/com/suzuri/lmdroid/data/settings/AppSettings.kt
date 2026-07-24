package com.suzuri.lmdroid.data.settings

data class AppSettings(
    val apiKey: String?,
    val model: String,
    val baseUrl: String,
) {
    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}
