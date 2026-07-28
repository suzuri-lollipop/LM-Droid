package com.suzuri.lmdroid.ui.assist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.MessageRole
import com.suzuri.lmdroid.data.repository.ConversationRepository
import com.suzuri.lmdroid.data.settings.SettingsRepository
import com.suzuri.lmdroid.data.tts.AssistSpeechPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the assistant overlay (AssistActivity): a single voice question sent through the exact
 * same [ConversationRepository.sendUserMessage] path as the main chat screen, so it shows up as a
 * normal conversation afterward — just a smaller, one-shot version of ChatViewModel's send/observe
 * pattern (no attachments, model switcher, or system prompts here). Uses
 * [SettingsRepository.currentAssistantSettings] rather than the plain chat selection, so Settings
 * → アシスタント can point this at a different (profile, model) pair (falling back to chat's own
 * selection when not overridden) — see [AssistUiState.modelProfileName], shown in place of the
 * static "アシスタント" title so it's clear which one is answering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistViewModel(
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val assistSpeechPlayer: AssistSpeechPlayer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AssistUiState())
    val uiState: StateFlow<AssistUiState> = _uiState.asStateFlow()

    // Created lazily on the first send (see ensureConversationId) and reused for any follow-up
    // question asked without leaving the overlay, so a follow-up continues the same conversation.
    private val conversationId = MutableStateFlow<Long?>(null)

    // The in-flight (or just-finished) speak() call for the latest reply — cancelling it (rather
    // than calling AssistSpeechPlayer.stop() directly) is what actually unblocks its suspended
    // synthesis/playback call via invokeOnCancellation, so a follow-up question or a retry doesn't
    // keep talking over the user.
    private var speechJob: Job? = null

    init {
        viewModelScope.launch {
            val settings = settingsRepository.currentAssistantSettings()
            _uiState.update { it.copy(markdownEnabled = settings.markdownEnabled, modelProfileName = settings.profileName) }
        }

        viewModelScope.launch {
            conversationId.filterNotNull().flatMapLatest { id ->
                conversationRepository.observeMessages(id)
            }.collect { entities ->
                val lastAssistantMessage = entities.lastOrNull { it.message.role == MessageRole.ASSISTANT } ?: return@collect
                _uiState.update {
                    it.copy(
                        assistantText = lastAssistantMessage.message.content,
                        isAssistantError = lastAssistantMessage.message.isError,
                    )
                }
            }
        }
    }

    fun onPartialTranscript(text: String) {
        _uiState.update { it.copy(transcript = text) }
    }

    fun onFinalTranscript(text: String) {
        _uiState.update { it.copy(transcript = text) }
        if (text.isNotBlank()) {
            send(text)
        }
    }

    /** [message] is already a final, localized display string — see VoiceInputState's onError. */
    fun onListeningError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    /** Clears the previous turn's transcript/error so the overlay reads as "ready to listen again" — the conversation id itself is untouched, so this continues the same conversation. */
    fun onAskFollowUp() {
        speechJob?.cancel()
        _uiState.update { it.copy(transcript = "", errorMessage = null, apiKeyMissing = false) }
    }

    /** Resets the UI state and increments triggerCount to signal AssistScreen to start listening again. Used when triggered by an external intent (e.g. earphone button). */
    fun onRetry() {
        speechJob?.cancel()
        _uiState.update {
            it.copy(
                transcript = "",
                hasSent = false,
                assistantText = "",
                isAssistantError = false,
                isStreaming = false,
                errorMessage = null,
                apiKeyMissing = false,
                triggerCount = it.triggerCount + 1,
            )
        }
    }

    private fun send(text: String) {
        if (_uiState.value.isStreaming) return
        _uiState.update {
            it.copy(hasSent = true, isStreaming = true, assistantText = "", isAssistantError = false, apiKeyMissing = false, errorMessage = null)
        }
        viewModelScope.launch {
            val id = ensureConversationId()
            val settings = settingsRepository.currentAssistantSettings()
            val result = conversationRepository.sendUserMessage(id, text, settingsOverride = settings)
            when (result) {
                is ConversationRepository.SendResult.Success -> Unit
                is ConversationRepository.SendResult.ApiKeyMissing -> _uiState.update { it.copy(apiKeyMissing = true) }
                is ConversationRepository.SendResult.Error -> {
                    Log.w(TAG, "Assist send failed: ${result.message}")
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
            }
            // Flips the UI to "done" immediately rather than waiting for speech playback to
            // finish — the reply is already fully shown, and speaking it is a background effect.
            _uiState.update { it.copy(isStreaming = false) }
            if (result is ConversationRepository.SendResult.Success) {
                speechJob = viewModelScope.launch { speakLatestReply(id) }
            }
        }
    }

    private suspend fun ensureConversationId(): Long {
        conversationId.value?.let { return it }
        val newId = conversationRepository.createNewConversation()
        conversationId.value = newId
        return newId
    }

    /**
     * Reads the just-committed reply straight from the DB (a fresh Flow.first(), not
     * uiState.assistantText) so this always speaks the final text even if the ViewModel's own
     * message-flow collector (see init) hasn't yet caught up with the last throttled write.
     */
    private suspend fun speakLatestReply(conversationId: Long) {
        val entities = conversationRepository.observeMessages(conversationId).first()
        val lastAssistantMessage = entities.lastOrNull { it.message.role == MessageRole.ASSISTANT } ?: return
        if (lastAssistantMessage.message.isError || lastAssistantMessage.message.content.isBlank()) return
        assistSpeechPlayer.speak(lastAssistantMessage.message.content)
    }

    private companion object {
        const val TAG = "AssistViewModel"
    }
}
