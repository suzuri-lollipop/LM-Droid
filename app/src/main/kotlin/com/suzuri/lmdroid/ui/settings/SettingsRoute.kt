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
    BraveSearchProfile,
    VoicevoxProfile,
    YoutubeDataApiProfile,
    OpenAiTtsProfile,
    System,
    WebSearch,
    Voice,
    Location,
    Alarm,
    Notes,
    Messaging,
    Music,
    ImageGenerationProfile,
    SystemPromptList,
    SystemPromptEdit,
    Assistant,
    Character,
}

fun SettingsRoute.parent(): SettingsRoute? = when (this) {
    SettingsRoute.Root -> null
    SettingsRoute.ApiSettings -> SettingsRoute.Root
    SettingsRoute.OpenAiCompatible -> SettingsRoute.ApiSettings
    SettingsRoute.BraveSearchProfile -> SettingsRoute.ApiSettings
    SettingsRoute.VoicevoxProfile -> SettingsRoute.ApiSettings
    SettingsRoute.YoutubeDataApiProfile -> SettingsRoute.ApiSettings
    SettingsRoute.OpenAiTtsProfile -> SettingsRoute.ApiSettings
    SettingsRoute.ImageGenerationProfile -> SettingsRoute.ApiSettings
    SettingsRoute.System -> SettingsRoute.Root
    SettingsRoute.WebSearch -> SettingsRoute.Root
    SettingsRoute.Voice -> SettingsRoute.Root
    SettingsRoute.Location -> SettingsRoute.Root
    SettingsRoute.Alarm -> SettingsRoute.Root
    SettingsRoute.Notes -> SettingsRoute.Root
    SettingsRoute.Messaging -> SettingsRoute.Root
    SettingsRoute.Music -> SettingsRoute.Root
    SettingsRoute.SystemPromptList -> SettingsRoute.Root
    SettingsRoute.SystemPromptEdit -> SettingsRoute.SystemPromptList
    SettingsRoute.Assistant -> SettingsRoute.Root
    SettingsRoute.Character -> SettingsRoute.Root
}
