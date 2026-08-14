package com.suzuri.lmdroid.data.character

/**
 * Which renderer draws the assistant character on the overlay (see CharacterStage). Only NONE
 * and STATIC are wired up so far; LIVE2D and MMD are reserved for the later phases of the
 * character plan (Cubism SDK for Native via JNI, and the NDK-native PMX/VMD renderer
 * respectively) — the settings screen shows them as disabled options until then.
 */
enum class CharacterModelType { NONE, STATIC, LIVE2D, MMD }

/**
 * The user's character-display preferences for the assistant overlay — see
 * SettingsRepository.characterSettings and Settings → キャラクター. When [modelType] is NONE the
 * overlay keeps its original bottom-sheet layout entirely (see AssistScreen).
 *
 * [modelPath]/[backgroundPath] point at files CharacterModelStore copied into internal storage
 * (never raw content:// uris — their read permission can lapse, while an internal copy is ours
 * forever). [scale] is the character's size relative to the stage's default; position is fixed
 * at bottom-center like a novel game's standing sprite.
 */
data class CharacterSettings(
    val modelType: CharacterModelType = CharacterModelType.NONE,
    val modelPath: String? = null,
    val backgroundPath: String? = null,
    val scale: Float = 1f,
    val typewriterEnabled: Boolean = true,
    val lipSyncEnabled: Boolean = true,
)
