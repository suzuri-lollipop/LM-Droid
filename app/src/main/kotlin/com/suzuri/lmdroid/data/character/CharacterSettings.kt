package com.suzuri.lmdroid.data.character

/**
 * Which renderer draws the assistant character on the overlay (see CharacterStage). NONE,
 * STATIC and MMD (NDK-native PMX/VMD renderer, Phase 4) are wired up; LIVE2D is reserved for
 * the Cubism SDK phase (Phase 3) and the settings screen shows it as a disabled option.
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
    // MMD only: the optional VMD motion looped while the model idles (CharacterModelStore's
    // SLOT_MOTION copy); null draws the model in its default pose with procedural blink/lip sync.
    val motionPath: String? = null,
    val backgroundPath: String? = null,
    val scale: Float = 1f,
    val typewriterEnabled: Boolean = true,
    val lipSyncEnabled: Boolean = true,
)
