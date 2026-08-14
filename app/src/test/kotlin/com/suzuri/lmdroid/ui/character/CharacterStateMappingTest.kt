package com.suzuri.lmdroid.ui.character

import org.junit.Assert.assertEquals
import org.junit.Test

class CharacterStateMappingTest {

    private fun state(
        isListening: Boolean = false,
        hasSent: Boolean = false,
        assistantText: String = "",
        isStreaming: Boolean = false,
        isSpeaking: Boolean = false,
        hasError: Boolean = false,
    ) = deriveCharacterState(isListening, hasSent, assistantText, isStreaming, isSpeaking, hasError)

    @Test
    fun `idle when nothing is happening`() {
        assertEquals(CharacterUiState.Idle, state())
    }

    @Test
    fun `listening while the mic is active`() {
        assertEquals(CharacterUiState.Listening, state(isListening = true))
    }

    @Test
    fun `listening beats speaking when both somehow overlap`() {
        assertEquals(CharacterUiState.Listening, state(isListening = true, isSpeaking = true))
    }

    @Test
    fun `thinking while a request was sent but nothing has arrived yet`() {
        assertEquals(CharacterUiState.Thinking, state(hasSent = true))
    }

    @Test
    fun `thinking while the reply is still streaming`() {
        assertEquals(CharacterUiState.Thinking, state(hasSent = true, assistantText = "部分的な応答", isStreaming = true))
    }

    @Test
    fun `speaking while tts playback is running`() {
        assertEquals(CharacterUiState.Speaking, state(hasSent = true, assistantText = "応答", isSpeaking = true))
    }

    @Test
    fun `speaking beats streaming since earlier sentences play while the rest generates`() {
        assertEquals(CharacterUiState.Speaking, state(hasSent = true, assistantText = "応答", isStreaming = true, isSpeaking = true))
    }

    @Test
    fun `error dominates every other state`() {
        assertEquals(CharacterUiState.Error, state(hasError = true))
        assertEquals(CharacterUiState.Error, state(hasError = true, isListening = true))
        assertEquals(CharacterUiState.Error, state(hasError = true, isSpeaking = true, isStreaming = true))
    }

    @Test
    fun `back to idle once the reply exists and nothing is playing`() {
        assertEquals(CharacterUiState.Idle, state(hasSent = true, assistantText = "完全な応答"))
    }
}
