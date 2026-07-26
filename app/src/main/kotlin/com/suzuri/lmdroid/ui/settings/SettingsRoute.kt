package com.suzuri.lmdroid.ui.settings

/**
 * The Settings tab drills down like the Android system Settings app (category → provider →
 * fields) rather than showing one flat form, so more categories/providers can be added later
 * without flattening the menu. Each route has exactly one parent, so "back" is a lookup rather
 * than a maintained stack.
 */
enum class SettingsRoute {
    Root,
    ApiSettings,
    OpenAiCompatible,
    System,
    WebSearch,
}

fun SettingsRoute.parent(): SettingsRoute? = when (this) {
    SettingsRoute.Root -> null
    SettingsRoute.ApiSettings -> SettingsRoute.Root
    SettingsRoute.OpenAiCompatible -> SettingsRoute.ApiSettings
    SettingsRoute.System -> SettingsRoute.Root
    SettingsRoute.WebSearch -> SettingsRoute.Root
}
