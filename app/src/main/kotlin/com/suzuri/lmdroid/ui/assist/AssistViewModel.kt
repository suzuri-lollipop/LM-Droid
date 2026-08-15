package com.suzuri.lmdroid.ui.assist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.MessageRole
import com.suzuri.lmdroid.data.repository.ConversationRepository
import com.suzuri.lmdroid.data.repository.TOOL_SIDE_EFFECT_DELAY_MS
import com.suzuri.lmdroid.data.settings.AssistantToolLaunchTiming
import com.suzuri.lmdroid.data.settings.SettingsRepository
import com.suzuri.lmdroid.data.tts.AssistSpeechPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
 *
 * Speech is dispatched sentence-by-sentence as the reply streams in (see [dispatchNewSpeechChunks])
 * rather than as one block after the whole reply is done — waiting for a long reply to finish
 * generating *and then* fully synthesizing before saying anything made the assistant feel far
 * slower than it needed to. It's a two-stage pipeline, not just a queue of text: [prepareJob]
 * drains [speechChannel] and calls [AssistSpeechPlayer.prepare] (the slow part — a VOICEVOX-
 * compatible profile's network synthesis) on each sentence, pushing the result onto
 * [preparedAudioChannel]; [playbackJob] drains *that* and calls [AssistSpeechPlayer.play].
 * Splitting these into separate queues/coroutines is what lets the next sentence's synthesis run
 * while the current one is still being played, instead of sitting idle until playback finishes —
 * a single queue of "synthesize then play" calls would serialize the two and reintroduce a gap
 * between every sentence.
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
    // Reset to null by onRetry(): a retrigger (assist gesture / voice-interaction show) is a new
    // session, and keeping the old id would leave the message-flow collector below observing the
    // previous conversation into the new session.
    private val conversationId = MutableStateFlow<Long?>(null)

    // The in-flight sendUserMessage coroutine of the current turn, if any — onRetry() cancels it
    // so a retrigger never inherits a previous session's still-streaming reply (or its deferred
    // tool side effects firing mid-new-session).
    private var sendJob: Job? = null

    // This turn's two-stage speech pipeline — sentence chunks detected by the message-flow
    // collector (see init) are pushed onto speechChannel; prepareJob synthesizes each one (the
    // slow, network-bound part for a VOICEVOX-compatible profile) and pushes the result onto
    // preparedAudioChannel; playbackJob drains that and actually plays them in order. All four are
    // per-turn: send() replaces them with fresh ones, so a follow-up's speech can never land in
    // the middle of the previous turn's queue.
    private var speechChannel: Channel<String>? = null
    private var preparedAudioChannel: Channel<AssistSpeechPlayer.PreparedSpeech>? = null
    private var prepareJob: Job? = null
    private var playbackJob: Job? = null

    // The id of the assistant message row spokenUpToIndex is counted against, and how many raw
    // characters of its content have already been dispatched to speechChannel. These are only
    // touched once a message with a *different* id than this shows up (see
    // dispatchNewSpeechChunks/flushRemainingSpeechChunk) — NOT eagerly reset in send() — because
    // inserting the next turn's own user message (the first DB write sendUserMessage makes)
    // already triggers a re-emission of the message-flow collector below, a beat before that
    // turn's own assistant placeholder row exists. At that moment "the last assistant message" is
    // still the *previous* turn's now-finished one; keying off its row id (which never changes
    // for as long as that row is the one being spoken) is what lets that redelivery be recognized
    // as already-fully-spoken rather than misread as an entire new reply to speak all over again.
    private var speakingMessageId: Long? = null
    private var spokenUpToIndex = 0

    init {
        viewModelScope.launch {
            // Collect live rather than reading once: this ViewModel outlives any single assist
            // session (singleInstance activity / voice-interaction session reuse), so a profile
            // switched in Settings must reach the name plate without a process restart.
            settingsRepository.assistantSettings.collect { settings ->
                _uiState.update { it.copy(markdownEnabled = settings.markdownEnabled, modelProfileName = settings.profileName) }
            }
        }

        viewModelScope.launch {
            settingsRepository.characterSettings.collect { character ->
                _uiState.update { it.copy(characterSettings = character) }
            }
        }

        viewModelScope.launch {
            assistSpeechPlayer.isSpeaking.collect { speaking ->
                _uiState.update { it.copy(isSpeaking = speaking) }
            }
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
                if (!lastAssistantMessage.message.isError) {
                    dispatchNewSpeechChunks(lastAssistantMessage.message.id, lastAssistantMessage.message.content)
                }
            }
        }
    }

    fun onPartialTranscript(text: String) {
        if (_uiState.value.isStreaming) return
        _uiState.update { it.copy(transcript = text) }
    }

    fun onFinalTranscript(text: String) {
        if (_uiState.value.isStreaming) return
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
        cancelSpeech()
        _uiState.update { it.copy(transcript = "", errorMessage = null, apiKeyMissing = false) }
    }

    /**
     * Starts a completely fresh session: cancels whatever the previous one was still doing,
     * clears its speech pipeline and conversation, resets the UI state, and increments
     * triggerCount to signal AssistScreen to start listening again. Called when triggered while
     * an instance is already alive (external intent / retrigger via the assist gesture, earphone
     * button, voice-interaction session re-show) — without the cancel/reset here, a previous
     * session's in-flight send kept streaming its reply (and firing its deferred tool side
     * effects) into the new one, which read as "the previous assistant carried over".
     */
    fun onRetry() {
        abandonCurrentTurn()
        conversationId.value = null
        speakingMessageId = null
        spokenUpToIndex = 0
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

    /**
     * The overlay went invisible (dismiss / back / home gesture — AssistScreen observes ON_STOP;
     // both hosts drive it) or a retrigger replaced this session. Abandon the turn entirely:
     * cancel in-flight generation, tear down the speech pipeline, and silence the player.
     * Cancelling the playback job alone isn't enough — it races a chunk just about to start,
     * which is exactly the "closed the overlay but it keeps talking" case — so the player itself
     * is stopped too (same reasoning as onCleared). Cancelling [sendJob] also keeps a dismissed
     * turn's deferred tool side effects from firing into a UI nobody is looking at.
     */
    private fun abandonCurrentTurn() {
        sendJob?.cancel()
        sendJob = null
        cancelSpeech()
        assistSpeechPlayer.stop()
    }

    /** See [abandonCurrentTurn] — called when the overlay leaves the foreground. */
    fun onHidden() {
        abandonCurrentTurn()
    }

    private fun send(text: String) {
        if (_uiState.value.isStreaming) return
        cancelSpeech()
        val textChannel = Channel<String>(capacity = Channel.UNLIMITED)
        val audioChannel = Channel<AssistSpeechPlayer.PreparedSpeech>(capacity = Channel.UNLIMITED)
        speechChannel = textChannel
        preparedAudioChannel = audioChannel
        prepareJob = viewModelScope.launch {
            for (chunk in textChannel) {
                val prepared = assistSpeechPlayer.prepare(chunk)
                if (prepared != null) audioChannel.send(prepared)
            }
            audioChannel.close()
        }
        playbackJob = viewModelScope.launch {
            for (prepared in audioChannel) {
                assistSpeechPlayer.play(prepared)
            }
        }

        _uiState.update {
            it.copy(hasSent = true, isStreaming = true, assistantText = "", isAssistantError = false, apiKeyMissing = false, errorMessage = null)
        }
        sendJob = viewModelScope.launch {
            val id = ensureConversationId()
            val settings = settingsRepository.currentAssistantSettings()
            val result = conversationRepository.sendUserMessage(id, text, settingsOverride = settings)
            val pendingSideEffects = when (result) {
                is ConversationRepository.SendResult.Success -> result.pendingSideEffects
                is ConversationRepository.SendResult.ApiKeyMissing -> {
                    _uiState.update { it.copy(apiKeyMissing = true) }
                    emptyList()
                }
                is ConversationRepository.SendResult.Error -> {
                    Log.w(TAG, "Assist send failed: ${result.message}")
                    _uiState.update { it.copy(errorMessage = result.message) }
                    result.pendingSideEffects
                }
            }
            // Flips the UI to "done" immediately rather than waiting for speech playback to
            // finish — the reply is already fully shown, and speaking it is a background effect.
            _uiState.update { it.copy(isStreaming = false) }
            if (result is ConversationRepository.SendResult.Success) {
                flushRemainingSpeechChunk(id)
            }
            // Captured before closing the channel below, which is what lets prepareJob/playbackJob
            // actually finish draining — see launchPendingSideEffects's own doc comment for why
            // this exact reference (rather than reading the `playbackJob` field again later) is
            // what a "wait for speech to finish" launch timing joins on.
            val thisTurnsPlaybackJob = playbackJob
            // Closing the text channel lets prepareJob's loop finish draining whatever's still
            // queued, then close audioChannel itself once every chunk has actually been prepared
            // — playbackJob only stops once *that* is drained too, so nothing queued gets dropped.
            textChannel.close()
            launchPendingSideEffects(pendingSideEffects, thisTurnsPlaybackJob)
        }
    }

    /**
     * Fires a reply's deferred tool side effects (see ConversationRepository) once this turn's
     * reply has actually started being heard, per Settings → アシスタント's "ツール実行のタイミング":
     * [AssistantToolLaunchTiming.WHILE_SPEAKING] (default) just waits a short beat, the same as
     * ChatViewModel, so speech keeps playing in the background while e.g. the share chooser opens;
     * [AssistantToolLaunchTiming.AFTER_SPEAKING] instead waits for [playbackJobAtDispatch] (this
     * turn's own playbackJob, captured by the caller before it can be replaced by a follow-up's
     * cancelSpeech()) to actually finish speaking. `Job.join()` also returns if that job gets
     * cancelled instead of completing normally (a follow-up question, or dismissing the overlay),
     * so this never blocks forever waiting on speech that isn't going to happen anymore.
     */
    private fun launchPendingSideEffects(actions: List<() -> Unit>, playbackJobAtDispatch: Job?) {
        if (actions.isEmpty()) return
        viewModelScope.launch {
            when (settingsRepository.currentAssistantToolLaunchTiming()) {
                AssistantToolLaunchTiming.AFTER_SPEAKING -> playbackJobAtDispatch?.join()
                AssistantToolLaunchTiming.WHILE_SPEAKING -> delay(TOOL_SIDE_EFFECT_DELAY_MS)
            }
            actions.forEach { action -> runCatching(action) }
        }
    }

    private suspend fun ensureConversationId(): Long {
        conversationId.value?.let { return it }
        val newId = conversationRepository.createNewConversation()
        conversationId.value = newId
        return newId
    }

    /**
     * Called on every reactive update of the streaming reply — queues each newly-completed
     * sentence (up to the last 。/！/？/line-break found in the not-yet-dispatched tail) so it
     * starts playing as soon as it's ready, rather than waiting for the whole reply. Text before
     * the first boundary in a given update, or a trailing partial sentence with no boundary yet,
     * just waits for the next update (or [flushRemainingSpeechChunk] once streaming ends).
     */
    private fun dispatchNewSpeechChunks(messageId: Long, fullText: String) {
        val channel = speechChannel ?: return
        adoptMessageIfNew(messageId)
        resetSpokenIndexIfContentReplaced(fullText)
        if (spokenUpToIndex >= fullText.length) return
        val unspoken = fullText.substring(spokenUpToIndex)
        val boundary = lastSentenceBoundary(unspoken) ?: return
        val chunk = unspoken.substring(0, boundary)
        spokenUpToIndex += boundary
        if (chunk.isNotBlank()) {
            channel.trySend(chunk)
        }
    }

    /**
     * Reads the just-committed reply straight from the DB (a fresh Flow.first(), not
     * uiState.assistantText) so this always sees the final text even if the ViewModel's own
     * message-flow collector (see init) hasn't yet caught up with the last throttled write, then
     * queues whatever's left after the last dispatched sentence — the tail rarely ends exactly on
     * a sentence boundary, and without this it would simply never get spoken.
     */
    private suspend fun flushRemainingSpeechChunk(conversationId: Long) {
        val entities = conversationRepository.observeMessages(conversationId).first()
        val lastAssistantMessage = entities.lastOrNull { it.message.role == MessageRole.ASSISTANT } ?: return
        if (lastAssistantMessage.message.isError) return
        adoptMessageIfNew(lastAssistantMessage.message.id)
        val fullText = lastAssistantMessage.message.content
        resetSpokenIndexIfContentReplaced(fullText)
        if (spokenUpToIndex >= fullText.length) return
        val remaining = fullText.substring(spokenUpToIndex)
        spokenUpToIndex = fullText.length
        if (remaining.isNotBlank()) {
            speechChannel?.trySend(remaining)
        }
    }

    /** Starts counting spokenUpToIndex fresh the first time [messageId] (a new turn's own assistant row) is actually seen — see [speakingMessageId]'s own doc comment for why this can't just happen eagerly in send() instead. */
    private fun adoptMessageIfNew(messageId: Long) {
        if (speakingMessageId != messageId) {
            speakingMessageId = messageId
            spokenUpToIndex = 0
        }
    }

    /** See [adjustedSpokenIndex] — the repository discards pre-tool-call preamble mid-turn, so content can shrink below what was already spoken. */
    private fun resetSpokenIndexIfContentReplaced(fullText: String) {
        spokenUpToIndex = adjustedSpokenIndex(spokenUpToIndex, fullText)
    }

    /**
     * Cancelling both jobs (rather than calling AssistSpeechPlayer.stop() directly) is what
     * actually unblocks whichever one is suspended mid synthesis/playback via
     * invokeOnCancellation — closing the channels on their own wouldn't interrupt whatever chunk
     * is already in flight. Between them, a follow-up question or a retry never keeps talking (or
     * synthesizing more to talk) over the user.
     */
    private fun cancelSpeech() {
        prepareJob?.cancel()
        prepareJob = null
        playbackJob?.cancel()
        playbackJob = null
        speechChannel?.close()
        speechChannel = null
        preparedAudioChannel?.close()
        preparedAudioChannel = null
    }

    // Job cancellation alone races a chunk that is just about to start playing (see
    // AssistSpeechPlayer's onPrepared guard), which is exactly the "overlay closed but it keeps
    // talking" case — stopping the player outright when the overlay goes away closes that gap.
    override fun onCleared() {
        super.onCleared()
        cancelSpeech()
        assistSpeechPlayer.stop()
    }

    private companion object {
        const val TAG = "AssistViewModel"
    }
}

// A newline is included alongside the Japanese sentence-enders so a list/paragraph break also
// starts speaking what's already arrived instead of waiting for the next 。/！/？.
private val SENTENCE_BOUNDARY_CHARS = charArrayOf('。', '！', '？', '\n')

/**
 * Index just past the last sentence-ending character in [text], or null if it doesn't contain one
 * yet. Kept as a top-level function, like [com.suzuri.lmdroid.data.tts.markdownToSpeechText], so
 * this is unit-testable without needing to construct an [AssistViewModel] and its dependencies.
 */
fun lastSentenceBoundary(text: String): Int? {
    val index = text.indexOfLast { it in SENTENCE_BOUNDARY_CHARS }
    return if (index == -1) null else index + 1
}

/**
 * The spoken-progress counter counts against the content as it was last seen, but the repository
 * discards the model's pre-tool-call preamble when a tool round starts (see
 * ConversationRepository's tool loop), so the content can shrink below what was already spoken.
 * In that case restart from the head of the replacement text rather than skipping its first
 * characters (or indexing past the end of it). Top-level for the same testability reason as
 * [lastSentenceBoundary].
 */
fun adjustedSpokenIndex(spokenUpToIndex: Int, fullText: String): Int =
    if (spokenUpToIndex > fullText.length) 0 else spokenUpToIndex
