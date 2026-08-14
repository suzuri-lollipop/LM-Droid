package com.suzuri.lmdroid.ui.character

/**
 * The conversation phase the assistant overlay is in, mapped onto the character's
 * expression/motion (see deriveCharacterState). Backends added later (Live2D via Cubism SDK,
 * MMD via the NDK renderer) implement [CharacterRenderer] and translate these into their own
 * parameter sets — the standing-sprite phase only uses it for lightweight animations.
 */
enum class CharacterUiState { Idle, Listening, Thinking, Speaking, Error }

/**
 * Backend-agnostic handle to whatever draws the assistant character. Implemented once each for
 * the static standing sprite (now), Live2D (Cubism SDK for Native via JNI — later phase) and MMD
 * (NDK-native PMX/VMD renderer — later phase); CharacterStage just drives whichever is active.
 */
interface CharacterRenderer {
    fun setState(state: CharacterUiState)

    /** [value] is 0.0 (closed) to 1.0 (open) — lip sync while TTS playback is running. */
    fun setMouthOpen(value: Float)

    fun release()
}

/**
 * Maps the overlay's flat UI flags onto one [CharacterUiState]. Kept as a top-level pure
 * function (like AssistScreen's lastSentenceBoundary) so the mapping is unit-testable without
 * constructing a ViewModel. Priority mirrors how the overlay itself reads: an error dominates
 * everything; an actively listening mic beats the reply being spoken (the character attends to
 * the user); TTS playback beats generation (a reply can keep streaming while earlier sentences
 * are still being spoken); generating with nothing to show yet is "thinking".
 */
fun deriveCharacterState(
    isListening: Boolean,
    hasSent: Boolean,
    assistantText: String,
    isStreaming: Boolean,
    isSpeaking: Boolean,
    hasError: Boolean,
): CharacterUiState = when {
    hasError -> CharacterUiState.Error
    isListening -> CharacterUiState.Listening
    isSpeaking -> CharacterUiState.Speaking
    isStreaming || (hasSent && assistantText.isBlank()) -> CharacterUiState.Thinking
    else -> CharacterUiState.Idle
}
