package com.suzuri.lmdroid.data.character

import com.suzuri.lmdroid.data.tts.MouthAmplitudeTracker

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
    // The RMS loudness (see MouthAmplitudeTracker) that maps to a fully-open mouth. Lower is more
    // sensitive (quieter audio opens the mouth further); higher needs louder audio to react at
    // all. Exposed as Settings → キャラクター's lip-sync sensitivity slider.
    val lipSyncThreshold: Float = MouthAmplitudeTracker.DEFAULT_REFERENCE_RMS,
    // MMD only: the on-screen display range, set from the live preview in Settings →
    // キャラクター (see CharacterSettingsScreen/MmdRenderer::setFraming). mmdZoom > 1 crops the
    // camera in on the model (e.g. a bust shot instead of full body); mmdPanX/mmdPanY shift that
    // framing sideways/vertically as a fraction of the model's bounds. Defaults reproduce the
    // original fixed full-body framing.
    val mmdZoom: Float = 1f,
    val mmdPanX: Float = 0f,
    val mmdPanY: Float = 0f,
)
