package com.suzuri.lmdroid.ui.assist

import com.suzuri.lmdroid.data.character.CharacterSettings

/**
 * Drives the assistant overlay (see AssistActivity) end to end: listen → transcribe → send →
 * stream the reply. A single always-forward flow (no back/forth navigation), so one flat state
 * is enough — contrast with ChatUiState, which has to represent a whole conversation.
 */
data class AssistUiState(
    // Live partial transcript while listening; holds the finalized text once recognition ends.
    // Whether we're *currently* listening is read straight from VoiceInputState.isListening (see
    // AssistScreen) rather than mirrored here — SpeechRecognizer can go quiet on timeout/no-match
    // without ever calling back into this ViewModel, which would otherwise leave a mirrored flag
    // stuck true.
    val transcript: String = "",
    val hasSent: Boolean = false,
    val assistantText: String = "",
    val isAssistantError: Boolean = false,
    val isStreaming: Boolean = false,
    val markdownEnabled: Boolean = true,
    // The (profile, model) pair this session is actually using — see
    // SettingsRepository.assistantSettings — shown in place of the static "アシスタント" title
    // (see AssistScreen) so it's clear which one replies came from. Null only when no chat
    // profile is configured at all yet, in which case the title falls back to the static label.
    val modelProfileName: String? = null,
    // Set when ConversationRepository.SendResult.ApiKeyMissing comes back — the caller (AssistScreen)
    // supplies the localized message, mirroring ChatUiState's own apiKeyMissing flag.
    val apiKeyMissing: Boolean = false,
    val errorMessage: String? = null,
    val triggerCount: Int = 0,
    // Settings → キャラクター — when modelType != NONE the overlay switches from the bottom
    // sheet to the novel-game-style stage layout with the character (see AssistScreen).
    val characterSettings: CharacterSettings = CharacterSettings(),
    // Mirrors AssistSpeechPlayer.isSpeaking so the character can show its "speaking" state /
    // lip-sync while the reply is being read aloud.
    val isSpeaking: Boolean = false,
    // Mirrors AssistSpeechPlayer.mouthAmplitude — the current TTS audio's live loudness (0..1),
    // so the character's mouth tracks the actual voice instead of a fixed open/close loop.
    val mouthAmplitude: Float = 0f,
)
