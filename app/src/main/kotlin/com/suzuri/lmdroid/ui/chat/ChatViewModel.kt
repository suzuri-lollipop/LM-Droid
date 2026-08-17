package com.suzuri.lmdroid.ui.chat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.attachment.AttachmentFileStore
import com.suzuri.lmdroid.data.attachment.AudioRecorder
import com.suzuri.lmdroid.data.attachment.SavedAttachment
import com.suzuri.lmdroid.data.db.MessageWithAttachments
import com.suzuri.lmdroid.data.db.ModelOptionRow
import com.suzuri.lmdroid.data.db.ThinkingEffort
import com.suzuri.lmdroid.data.db.ThinkingTimelineEntry
import com.suzuri.lmdroid.data.repository.ApiProfileRepository
import com.suzuri.lmdroid.data.repository.ConversationRepository
import com.suzuri.lmdroid.data.repository.SkillRepository
import com.suzuri.lmdroid.data.repository.SystemPromptRepository
import com.suzuri.lmdroid.data.repository.TOOL_SIDE_EFFECT_DELAY_MS
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val apiProfileRepository: ApiProfileRepository,
    private val attachmentFileStore: AttachmentFileStore,
    private val audioRecorder: AudioRecorder,
    private val systemPromptRepository: SystemPromptRepository,
    private val skillRepository: SkillRepository,
    private val json: Json,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // A StateFlow (not a plain var) so switching conversations can drive observeMessages via
    // flatMapLatest below, instead of only ever observing whichever conversation was active when
    // init{} first ran. null is a real, stable state here (not just "still loading"): it means
    // "a fresh new conversation that hasn't been written to the database yet" — see
    // ensureConversationId() and startNewConversation().
    private val conversationId = MutableStateFlow<Long?>(null)
    private var sendJob: Job? = null

    init {
        viewModelScope.launch {
            conversationId.value = conversationRepository.getInitialConversationId()
        }

        viewModelScope.launch {
            conversationId.filterNotNull().flatMapLatest { id ->
                conversationRepository.observeMessages(id)
            }.collect { entities ->
                _uiState.update { state -> state.copy(messages = entities.map { it.toUiModel() }) }
            }
        }

        // Drives the top bar title shown for the Chat screen (see MainActivity), so it tracks
        // the active conversation's title — including once the LLM-generated title lands.
        viewModelScope.launch {
            conversationId.filterNotNull().flatMapLatest { id ->
                conversationRepository.observeConversation(id)
            }.collect { conversation ->
                _uiState.update { state -> state.copy(conversationTitle = conversation?.title.orEmpty()) }
            }
        }

        viewModelScope.launch {
            settingsRepository.chatSettings.collect { settings ->
                _uiState.update { state ->
                    state.copy(
                        apiKeyMissing = settings.apiKey.isNullOrBlank(),
                        markdownEnabled = settings.markdownEnabled,
                        thinkingEffort = settings.thinkingEffort,
                        memoryEnabled = settings.memoryEnabled,
                    )
                }
            }
        }

        viewModelScope.launch {
            combine(
                systemPromptRepository.observePrompts(),
                systemPromptRepository.selectedPromptIds,
            ) { prompts, selectedIds ->
                prompts.map { SystemPromptOptionUiModel(id = it.id, name = it.name) } to selectedIds
            }.collect { (prompts, selectedIds) ->
                _uiState.update { state -> state.copy(systemPrompts = prompts, selectedSystemPromptIds = selectedIds) }
            }
        }

        viewModelScope.launch {
            combine(
                skillRepository.observeSkills(),
                skillRepository.selectedSkillIds,
            ) { skills, selectedIds ->
                skills.map { SkillOptionUiModel(id = it.id, name = it.name, description = it.description) } to selectedIds
            }.collect { (skills, selectedIds) ->
                _uiState.update { state -> state.copy(skills = skills, selectedSkillIds = selectedIds) }
            }
        }

        // Keeps the chat-screen model switcher in sync with every enabled profile's registered
        // models, and — the first time any become available with nothing explicitly chosen yet —
        // auto-picks one so merely enabling a profile is enough to start chatting, without also
        // having to visit the switcher.
        viewModelScope.launch {
            combine(
                apiProfileRepository.observeEnabledModelOptions(),
                settingsRepository.selectedChatModel,
            ) { options, selected -> options to selected }
                .collect { (options, selected) ->
                    _uiState.update { it.copy(availableModels = options, selectedModel = selected) }
                    if (selected == null && options.isNotEmpty()) {
                        val first = options.first()
                        settingsRepository.setSelectedChatModel(first.profileId, first.modelId)
                    }
                }
        }

        // Best-effort, once per app session: personalizes the empty-conversation suggestion rows
        // from past conversation topics. Starts at Loading (shown as a skeleton animation) and
        // resolves to Generated on success, or Fallback (static starter prompts) on failure or
        // when there's no history yet to base them on.
        viewModelScope.launch {
            val suggestions = conversationRepository.generateSuggestedPrompts()
            _uiState.update { state ->
                state.copy(
                    suggestionsState = if (!suggestions.isNullOrEmpty()) {
                        SuggestionsUiState.Generated(suggestions)
                    } else {
                        SuggestionsUiState.Fallback
                    },
                )
            }
        }
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    /** Appends dictated speech to whatever's already typed (rather than replacing it outright). */
    fun onVoiceInputResult(text: String) {
        _uiState.update { state ->
            state.copy(input = if (state.input.isBlank()) text else "${state.input} $text")
        }
    }

    fun onSend() {
        performSend(_uiState.value.input.trim(), _uiState.value.pendingAttachments)
    }

    private fun performSend(text: String, attachments: List<PendingAttachmentUiModel>) {
        if ((text.isEmpty() && attachments.isEmpty()) || _uiState.value.isStreaming) return

        val forcedSkillId = _uiState.value.forcedSkillId
        _uiState.update { it.copy(input = "", pendingAttachments = emptyList(), errorMessage = null, forcedSkillId = null) }
        launchGeneration {
            val id = ensureConversationId()
            conversationRepository.sendUserMessage(
                id,
                text,
                attachments.map { SavedAttachment(filePath = it.filePath, mimeType = it.mimeType) },
                forcedSkillId = forcedSkillId,
            )
        }
    }

    /** Copies the picked image into app storage (see AttachmentFileStore) and stages it above the input bar. */
    fun onFileAttached(uri: Uri) {
        viewModelScope.launch {
            val saved = runCatching { attachmentFileStore.save(uri) }
                .onFailure { e -> Log.w(TAG, "Failed to save attachment $uri", e) }
                .getOrNull()
            if (saved == null) {
                _uiState.update { it.copy(errorMessage = "画像の読み込みに失敗しました。") }
                return@launch
            }
            _uiState.update { state ->
                state.copy(
                    pendingAttachments = state.pendingAttachments + PendingAttachmentUiModel(
                        id = UUID.randomUUID().toString(),
                        filePath = saved.filePath,
                        mimeType = saved.mimeType,
                    ),
                )
            }
        }
    }

    /** Removes a not-yet-sent attachment and cleans up its now-unused file. */
    fun onRemovePendingAttachment(id: String) {
        val toRemove = _uiState.value.pendingAttachments.find { it.id == id } ?: return
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments.filterNot { a -> a.id == id }) }
        viewModelScope.launch { attachmentFileStore.delete(toRemove.filePath) }
    }

    /**
     * Starts recording a voice message (the composer's mic long-press) — a separate mechanism
     * from [onVoiceInputResult]'s on-device dictation: this captures raw audio to attach and send
     * to an audio-input-capable model, rather than converting speech to text locally. The caller
     * is expected to have already confirmed RECORD_AUDIO permission.
     */
    fun onVoiceRecordingStart() {
        _uiState.update { it.copy(isRecordingVoiceMessage = true) }
        audioRecorder.start(viewModelScope)
    }

    /**
     * Stops recording and sends immediately — unlike a picked image, a voice message isn't staged
     * for the user to review and separately hit Send on; releasing the mic *is* the send action,
     * the same way letting go in a voice-messaging app sends it. Whatever's currently typed (if
     * anything) goes along with it, exactly as a normal send would.
     */
    fun onVoiceRecordingStop() {
        viewModelScope.launch {
            val file = audioRecorder.stop()
            _uiState.update { it.copy(isRecordingVoiceMessage = false) }
            if (file == null) return@launch
            val voiceAttachment = PendingAttachmentUiModel(
                id = UUID.randomUUID().toString(),
                filePath = file.absolutePath,
                mimeType = "audio/wav",
            )
            performSend(_uiState.value.input.trim(), _uiState.value.pendingAttachments + voiceAttachment)
        }
    }

    /**
     * Lazily creates and persists the conversation the moment it's actually needed (the first
     * message being sent) — until then, conversationId stays null and nothing shows up in the
     * History list. Safe to call repeatedly: once a real id is assigned, it's reused.
     */
    private suspend fun ensureConversationId(): Long {
        conversationId.value?.let { return it }
        val newId = conversationRepository.createNewConversation()
        conversationId.value = newId
        return newId
    }

    /**
     * Edits a previously-sent user message and regenerates the reply from that point (ChatGPT/
     * Claude-style "edit and regenerate"). Cancels any in-progress generation first, since editing
     * implies the user wants a redo rather than the response currently streaming in.
     */
    fun onEditMessage(messageId: Long, newText: String) {
        val trimmed = newText.trim()
        val currentConversationId = conversationId.value ?: return
        if (trimmed.isEmpty()) return

        sendJob?.cancel()
        _uiState.update { it.copy(errorMessage = null) }
        launchGeneration { conversationRepository.editMessageAndRegenerate(currentConversationId, messageId, trimmed) }
    }

    /**
     * Regenerates an assistant reply without needing to edit the user's message first (a plain
     * "retry" affordance shown directly on the generated text).
     */
    fun onRegenerateResponse(messageId: Long) {
        val currentConversationId = conversationId.value ?: return

        sendJob?.cancel()
        _uiState.update { it.copy(errorMessage = null) }
        launchGeneration { conversationRepository.regenerateResponse(currentConversationId, messageId) }
    }

    fun onStopGeneration() {
        sendJob?.cancel()
    }

    /**
     * Updates immediately (rather than waiting for the settings Flow to round-trip through
     * DataStore) so the switch feels instant, and persists it so it's remembered next launch.
     */
    fun onMarkdownEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(markdownEnabled = enabled) }
        viewModelScope.launch { settingsRepository.saveMarkdownEnabled(enabled) }
    }

    /** Same immediate-update-then-persist pattern as [onMarkdownEnabledChange] — see [com.suzuri.lmdroid.data.settings.AppSettings.thinkingEffort]. */
    fun onThinkingEffortChange(effort: ThinkingEffort) {
        _uiState.update { it.copy(thinkingEffort = effort) }
        viewModelScope.launch { settingsRepository.saveThinkingEffort(effort) }
    }

    /** Same immediate-update-then-persist pattern as [onMarkdownEnabledChange] — see [com.suzuri.lmdroid.data.settings.AppSettings.memoryEnabled]. */
    fun onMemoryEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(memoryEnabled = enabled) }
        viewModelScope.launch { settingsRepository.saveMemoryEnabled(enabled) }
    }

    /** Toggles whether a saved system prompt is active, from [com.suzuri.lmdroid.ui.chat.components.SystemPromptDialog] — several may be active simultaneously. */
    fun onToggleSystemPrompt(id: Long) {
        viewModelScope.launch { systemPromptRepository.togglePrompt(id) }
    }

    /** Toggles whether a saved skill is active (advertised to the model), from [com.suzuri.lmdroid.ui.chat.components.SkillDialog] — several may be active simultaneously. */
    fun onToggleSkill(id: Long) {
        viewModelScope.launch { skillRepository.toggleSkill(id) }
    }

    /**
     * Explicitly picks a skill (from [com.suzuri.lmdroid.ui.chat.components.SkillDialog]'s "使う"
     * action) to force into just the next message sent, regardless of whether it's in the active/
     * advertised set — the user's own counterpart to the model deciding for itself via the
     * "use_skill" tool. Shown as a removable chip above the composer until sent or cleared.
     */
    fun onForceSkillForNextMessage(id: Long) {
        _uiState.update { it.copy(forcedSkillId = id) }
    }

    fun onClearForcedSkill() {
        _uiState.update { it.copy(forcedSkillId = null) }
    }

    /**
     * Switches which enabled profile/model pair chat uses, from the switcher shown on this
     * screen. Also seeds the 思考 effort selector and 記憶 toggle from the newly selected
     * profile's configured defaults (see ApiProfileEntity.defaultThinkingEffort/
     * defaultMemoryEnabled) — e.g. a Gemma profile the user runs without thinking flips the
     * selector to OFF automatically, a Qwen3.8 profile that reasons by default flips it to MEDIUM.
     * A profile with no configured default (null) leaves the selector/toggle as the user last set it.
     */
    fun onSelectModel(option: ModelOptionRow) {
        viewModelScope.launch { settingsRepository.setSelectedChatModel(option.profileId, option.modelId) }
        option.defaultThinkingEffort?.let(::onThinkingEffortChange)
        option.defaultMemoryEnabled?.let(::onMemoryEnabledChange)
    }

    private fun launchGeneration(block: suspend () -> ConversationRepository.SendResult) {
        _uiState.update { it.copy(isStreaming = true) }
        sendJob = viewModelScope.launch {
            try {
                when (val result = block()) {
                    is ConversationRepository.SendResult.Success -> launchPendingSideEffects(result.pendingSideEffects)
                    is ConversationRepository.SendResult.ApiKeyMissing ->
                        _uiState.update { it.copy(apiKeyMissing = true) }
                    is ConversationRepository.SendResult.Error -> {
                        _uiState.update { it.copy(errorMessage = result.message) }
                        launchPendingSideEffects(result.pendingSideEffects)
                    }
                }
            } finally {
                // Runs on normal completion, on error, and on cancellation (from onStopGeneration
                // or onEditMessage) alike — MutableStateFlow.update isn't a suspend call, so it's
                // safe here even though the job may already be cancelled.
                _uiState.update { it.copy(isStreaming = false) }
            }
        }
    }

    /** Fires a reply's deferred tool side effects (see ConversationRepository) a beat after it's already on screen, so e.g. the share chooser for send_message doesn't interrupt the screen before the reply that explains it has appeared. Runs in its own job so it isn't tied to (or cancelled along with) [sendJob]. */
    private fun launchPendingSideEffects(actions: List<() -> Unit>) {
        if (actions.isEmpty()) return
        viewModelScope.launch {
            delay(TOOL_SIDE_EFFECT_DELAY_MS)
            actions.forEach { action -> runCatching(action) }
        }
    }

    /**
     * Used by the History screen to check whether a conversation about to be deleted is the one
     * currently open here, so it can redirect this ViewModel to a fresh conversation instead of
     * leaving it pointed at a now-deleted row.
     */
    fun currentConversationId(): Long? = conversationId.value

    fun switchToConversation(id: Long) {
        sendJob?.cancel()
        conversationId.value = id
    }

    /**
     * Resets to a fresh, not-yet-persisted conversation (see ensureConversationId()) — nothing is
     * written to the database, and nothing shows up in History, until a message is actually sent
     * from it. This is a plain synchronous state reset (no suspend DB call), so mashing "new
     * conversation" repeatedly is harmless: every call just re-applies the same blank state.
     */
    fun startNewConversation() {
        sendJob?.cancel()
        audioRecorder.cancel()
        conversationId.value = null
        val orphanedAttachments = _uiState.value.pendingAttachments
        _uiState.update {
            it.copy(
                messages = emptyList(),
                conversationTitle = "",
                input = "",
                errorMessage = null,
                pendingAttachments = emptyList(),
                isRecordingVoiceMessage = false,
            )
        }
        if (orphanedAttachments.isNotEmpty()) {
            viewModelScope.launch { attachmentFileStore.deleteAll(orphanedAttachments.map { it.filePath }) }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun MessageWithAttachments.toUiModel() = MessageUiModel(
        id = message.id,
        role = message.role,
        content = message.content,
        isError = message.isError,
        thinkingTimeline = message.thinkingTimelineJson?.let { raw ->
            runCatching { json.decodeFromString(ListSerializer(ThinkingTimelineEntry.serializer()), raw) }
                .onFailure { e -> Log.w(TAG, "Failed to parse thinking timeline for message ${message.id}", e) }
                .getOrNull()
        }.orEmpty(),
        attachments = attachments.map { MessageAttachmentUiModel(filePath = it.filePath, mimeType = it.mimeType) },
    )

    private companion object {
        const val TAG = "ChatViewModel"
    }
}
