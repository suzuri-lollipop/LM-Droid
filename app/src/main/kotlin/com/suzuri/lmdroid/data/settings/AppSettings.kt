package com.suzuri.lmdroid.data.settings

data class AppSettings(
    val apiKey: String?,
    val model: String,
) {
    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}
