package com.suzuri.lmdroid.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.suzuri.lmdroid.data.db.ApiProfileDao
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** A specific (profile, model) pair — identifies exactly which credentials + model to use for a given purpose. */
data class SelectedModel(val profileId: Long, val model: String)

/**
 * When the Assistant overlay actually launches a tool's deferred side effect (e.g. the
 * send_message share chooser, or the clock app for set_alarm/set_timer) relative to
 * [com.suzuri.lmdroid.data.tts.AssistSpeechPlayer] speaking the reply that describes it — see
 * ConversationRepository's pendingSideEffects queue and AssistViewModel.launchPendingSideEffects.
 */
enum class AssistantToolLaunchTiming { WHILE_SPEAKING, AFTER_SPEAKING }

/**
 * Resolves which (profile, model) pair is used for (a) the chat screen — adjustable there —
 * (b) background "system" tasks (auto-titling, prompt suggestions) — adjustable from Settings →
 * システム — and (c) the assistant overlay (Settings → アシスタント), all falling back to the chat
 * selection when not explicitly overridden. Also tracks app-wide preferences unrelated to any one
 * profile, like [AppSettings.markdownEnabled], the Web検索 on/off toggle, and which registered
 * [ApiProfileEntity] (providerType == PROVIDER_BRAVE_SEARCH) is currently active for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepository(
    private val context: Context,
    private val cipher: ApiKeyCipher,
    private val apiProfileDao: ApiProfileDao,
) {
    val selectedChatModel: Flow<SelectedModel?> =
        context.settingsDataStore.data.map { it.toSelectedModel(KEY_CHAT_PROFILE_ID, KEY_CHAT_MODEL) }

    val selectedSystemModel: Flow<SelectedModel?> =
        context.settingsDataStore.data.map { it.toSelectedModel(KEY_SYSTEM_PROFILE_ID, KEY_SYSTEM_MODEL) }

    val selectedAssistantModel: Flow<SelectedModel?> =
        context.settingsDataStore.data.map { it.toSelectedModel(KEY_ASSISTANT_PROFILE_ID, KEY_ASSISTANT_MODEL) }

    private val markdownEnabledFlow: Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_MARKDOWN_ENABLED] ?: true }

    val chatSettings: Flow<AppSettings> = selectedChatModel.flatMapLatest { selected -> resolve(selected) }

    /** Falls back to the chat selection when no system-specific override has been chosen. */
    val systemSettings: Flow<AppSettings> = selectedSystemModel.flatMapLatest { systemSelected ->
        if (systemSelected != null) resolve(systemSelected) else chatSettings
    }

    /** Falls back to the chat selection when no assistant-specific override has been chosen — see AssistViewModel, which uses this instead of [chatSettings] for the assistant overlay's replies. */
    val assistantSettings: Flow<AppSettings> = selectedAssistantModel.flatMapLatest { assistantSelected ->
        if (assistantSelected != null) resolve(assistantSelected) else chatSettings
    }

    suspend fun currentChatSettings(): AppSettings = chatSettings.first()

    suspend fun currentSystemSettings(): AppSettings = systemSettings.first()

    suspend fun currentAssistantSettings(): AppSettings = assistantSettings.first()

    suspend fun setSelectedChatModel(profileId: Long, model: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_CHAT_PROFILE_ID] = profileId
            prefs[KEY_CHAT_MODEL] = model
        }
    }

    /** Null clears the override, falling back to the chat selection again. */
    suspend fun setSelectedSystemModel(profileId: Long?, model: String?) {
        context.settingsDataStore.edit { prefs ->
            if (profileId != null && model != null) {
                prefs[KEY_SYSTEM_PROFILE_ID] = profileId
                prefs[KEY_SYSTEM_MODEL] = model
            } else {
                prefs.remove(KEY_SYSTEM_PROFILE_ID)
                prefs.remove(KEY_SYSTEM_MODEL)
            }
        }
    }

    /** Null clears the override, falling back to the chat selection again. */
    suspend fun setSelectedAssistantModel(profileId: Long?, model: String?) {
        context.settingsDataStore.edit { prefs ->
            if (profileId != null && model != null) {
                prefs[KEY_ASSISTANT_PROFILE_ID] = profileId
                prefs[KEY_ASSISTANT_MODEL] = model
            } else {
                prefs.remove(KEY_ASSISTANT_PROFILE_ID)
                prefs.remove(KEY_ASSISTANT_MODEL)
            }
        }
    }

    suspend fun saveMarkdownEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_MARKDOWN_ENABLED] = enabled }
    }

    /** Whether the Brave Search harness (see ConversationRepository) is forced on for every message. */
    val braveSearchEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_BRAVE_SEARCH_ENABLED] ?: false }

    suspend fun currentBraveSearchEnabled(): Boolean = braveSearchEnabled.first()

    suspend fun setBraveSearchEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_BRAVE_SEARCH_ENABLED] = enabled }
    }

    /** Which registered [ApiProfileEntity] (providerType == PROVIDER_BRAVE_SEARCH) is currently active for the web_search/fetch_webpage tools — at most one at a time, the same way the chat/system model selections point at an ApiProfileEntity by id. Null means none selected. */
    val selectedWebSearchProfileId: Flow<Long?> =
        context.settingsDataStore.data.map { it[KEY_SELECTED_WEB_SEARCH_PROFILE_ID] }

    suspend fun currentSelectedWebSearchProfileId(): Long? = selectedWebSearchProfileId.first()

    suspend fun setSelectedWebSearchProfileId(id: Long?) {
        context.settingsDataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_SELECTED_WEB_SEARCH_PROFILE_ID) else prefs[KEY_SELECTED_WEB_SEARCH_PROFILE_ID] = id
        }
    }

    /** The active Brave Search profile's decrypted API key, or null when none is selected, the selected one was since deleted, or decryption fails. */
    suspend fun currentWebSearchApiKey(): String? {
        val id = currentSelectedWebSearchProfileId() ?: return null
        val profile = apiProfileDao.getById(id) ?: return null
        val ciphertext = profile.apiKeyCiphertext
        val iv = profile.apiKeyIv
        if (ciphertext == null || iv == null) return null
        return runCatching { cipher.decrypt(ciphertext, iv) }.getOrNull()
    }

    /** How many web_search tool round-trips one reply may make before being forced to answer with what it has. 0 (the default) means unconditionally allowed, up to ConversationRepository's own hard safety ceiling. */
    val webSearchMaxToolRounds: Flow<Int> =
        context.settingsDataStore.data.map { it[KEY_WEB_SEARCH_MAX_TOOL_ROUNDS] ?: 0 }

    suspend fun currentWebSearchMaxToolRounds(): Int = webSearchMaxToolRounds.first()

    suspend fun setWebSearchMaxToolRounds(rounds: Int) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_WEB_SEARCH_MAX_TOOL_ROUNDS] = rounds.coerceAtLeast(0) }
    }

    /** Which saved [com.suzuri.lmdroid.data.db.SystemPromptEntity]s (zero or more) are currently active — see SystemPromptRepository, which resolves these to the prompts' actual text. */
    val selectedSystemPromptIds: Flow<Set<Long>> =
        context.settingsDataStore.data.map { prefs ->
            prefs[KEY_SELECTED_SYSTEM_PROMPT_IDS]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        }

    suspend fun currentSelectedSystemPromptIds(): Set<Long> = selectedSystemPromptIds.first()

    suspend fun setSelectedSystemPromptIds(ids: Set<Long>) {
        context.settingsDataStore.edit { prefs ->
            if (ids.isEmpty()) {
                prefs.remove(KEY_SELECTED_SYSTEM_PROMPT_IDS)
            } else {
                prefs[KEY_SELECTED_SYSTEM_PROMPT_IDS] = ids.map { it.toString() }.toSet()
            }
        }
    }

    /** Whether the "get_current_location" tool (see ConversationRepository) is offered to the model — the caller (WebSearchSettingsScreen) is expected to only turn this on once location permission is actually granted. */
    val locationEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_LOCATION_ENABLED] ?: false }

    suspend fun currentLocationEnabled(): Boolean = locationEnabled.first()

    suspend fun setLocationEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_LOCATION_ENABLED] = enabled }
    }

    /** Which registered [ApiProfileEntity] (providerType == PROVIDER_VOICEVOX_COMPATIBLE) reads the assistant overlay's replies aloud (see AssistSpeechPlayer) — null means this device's own built-in text-to-speech. */
    val selectedTtsProfileId: Flow<Long?> = context.settingsDataStore.data.map { it[KEY_SELECTED_TTS_PROFILE_ID] }

    suspend fun currentSelectedTtsProfileId(): Long? = selectedTtsProfileId.first()

    suspend fun setSelectedTtsProfileId(id: Long?) {
        context.settingsDataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_SELECTED_TTS_PROFILE_ID) else prefs[KEY_SELECTED_TTS_PROFILE_ID] = id
        }
    }

    /** The active VOICEVOX-compatible profile, or null when none is selected (or the selected one was since deleted) — AssistSpeechPlayer falls back to on-device speech in that case. */
    suspend fun currentTtsProfile(): ApiProfileEntity? {
        val id = currentSelectedTtsProfileId() ?: return null
        return apiProfileDao.getById(id)
    }

    /** See [AssistantToolLaunchTiming] — defaults to WHILE_SPEAKING (the previous, only behavior). */
    val assistantToolLaunchTiming: Flow<AssistantToolLaunchTiming> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_ASSISTANT_TOOL_LAUNCH_TIMING]?.let { runCatching { AssistantToolLaunchTiming.valueOf(it) }.getOrNull() }
            ?: AssistantToolLaunchTiming.WHILE_SPEAKING
    }

    suspend fun currentAssistantToolLaunchTiming(): AssistantToolLaunchTiming = assistantToolLaunchTiming.first()

    suspend fun setAssistantToolLaunchTiming(timing: AssistantToolLaunchTiming) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_ASSISTANT_TOOL_LAUNCH_TIMING] = timing.name }
    }

    /** Whether the "set_alarm"/"set_timer" tools (see ConversationRepository, DeviceAlarmController) are offered to the model — off by default, since (unlike a read-only lookup) these actually create device alarms/timers as soon as the model calls them. */
    val alarmToolEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_ALARM_TOOL_ENABLED] ?: false }

    suspend fun currentAlarmToolEnabled(): Boolean = alarmToolEnabled.first()

    suspend fun setAlarmToolEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_ALARM_TOOL_ENABLED] = enabled }
    }

    /** Whether the "create_note" tool (see ConversationRepository, DeviceNoteController) is offered to the model — off by default, since (unlike a read-only lookup) this actually opens a note app pre-filled with content as soon as the model calls it. */
    val notesToolEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_NOTES_TOOL_ENABLED] ?: false }

    suspend fun currentNotesToolEnabled(): Boolean = notesToolEnabled.first()

    suspend fun setNotesToolEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_NOTES_TOOL_ENABLED] = enabled }
    }

    /** Which installed app's package name (see DeviceNoteController.installedNoteApps) the "create_note" tool targets directly — null falls back to the system share chooser every time the tool is called. */
    val preferredNoteAppPackage: Flow<String?> = context.settingsDataStore.data.map { it[KEY_PREFERRED_NOTE_APP_PACKAGE] }

    suspend fun currentPreferredNoteAppPackage(): String? = preferredNoteAppPackage.first()

    suspend fun setPreferredNoteAppPackage(packageName: String?) {
        context.settingsDataStore.edit { prefs ->
            if (packageName == null) prefs.remove(KEY_PREFERRED_NOTE_APP_PACKAGE) else prefs[KEY_PREFERRED_NOTE_APP_PACKAGE] = packageName
        }
    }

    /** Whether the "send_message" tool (see ConversationRepository, DeviceMessageController) is offered to the model — off by default, since (unlike a read-only lookup) this actually opens a messaging app pre-filled with content as soon as the model calls it. */
    val messagingToolEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_MESSAGING_TOOL_ENABLED] ?: false }

    suspend fun currentMessagingToolEnabled(): Boolean = messagingToolEnabled.first()

    suspend fun setMessagingToolEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_MESSAGING_TOOL_ENABLED] = enabled }
    }

    /** Which installed app's package name (see DeviceMessageController.installedMessagingApps) the "send_message" tool targets directly — null falls back to the system share chooser every time the tool is called. */
    val preferredMessagingAppPackage: Flow<String?> = context.settingsDataStore.data.map { it[KEY_PREFERRED_MESSAGING_APP_PACKAGE] }

    suspend fun currentPreferredMessagingAppPackage(): String? = preferredMessagingAppPackage.first()

    suspend fun setPreferredMessagingAppPackage(packageName: String?) {
        context.settingsDataStore.edit { prefs ->
            if (packageName == null) prefs.remove(KEY_PREFERRED_MESSAGING_APP_PACKAGE) else prefs[KEY_PREFERRED_MESSAGING_APP_PACKAGE] = packageName
        }
    }

    /** Whether the "play_music" tool (see ConversationRepository, DeviceMusicController) is offered to the model — off by default, since (unlike a read-only lookup) this actually opens a music app and starts playback as soon as the model calls it. */
    val musicToolEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_MUSIC_TOOL_ENABLED] ?: false }

    suspend fun currentMusicToolEnabled(): Boolean = musicToolEnabled.first()

    suspend fun setMusicToolEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_MUSIC_TOOL_ENABLED] = enabled }
    }

    /** Whether the background wake word listener (WakeWordService) is active. */
    val wakeWordEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_WAKE_WORD_ENABLED] ?: false }

    suspend fun currentWakeWordEnabled(): Boolean = wakeWordEnabled.first()

    suspend fun setWakeWordEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_WAKE_WORD_ENABLED] = enabled }
    }

    /** The phrase that triggers the assistant overlay — dynamic Vosk grammar means this can be anything, though localized guidance recommends 3-5 syllables for stability. */
    val wakeWord: Flow<String> = context.settingsDataStore.data.map { it[KEY_WAKE_WORD] ?: "エルエムドロイド" }

    suspend fun currentWakeWord(): String = wakeWord.first()

    suspend fun setWakeWord(word: String) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_WAKE_WORD] = word }
    }

    /** Which speech recognition model is used for voice input. */
    val selectedSttModelId: Flow<String> = context.settingsDataStore.data.map { it[KEY_SELECTED_STT_MODEL_ID] ?: "vosk-jp-small" }

    suspend fun currentSelectedSttModelId(): String = selectedSttModelId.first()

    suspend fun setSelectedSttModelId(id: String) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_SELECTED_STT_MODEL_ID] = id }
    }

    /** Which installed app's package name (see DeviceMusicController.installedMusicApps) the "play_music" tool targets directly — null lets the system resolve it itself (its own disambiguation dialog if more than one app can handle it and none is set as default). */
    val preferredMusicAppPackage: Flow<String?> = context.settingsDataStore.data.map { it[KEY_PREFERRED_MUSIC_APP_PACKAGE] }

    suspend fun currentPreferredMusicAppPackage(): String? = preferredMusicAppPackage.first()

    suspend fun setPreferredMusicAppPackage(packageName: String?) {
        context.settingsDataStore.edit { prefs ->
            if (packageName == null) prefs.remove(KEY_PREFERRED_MUSIC_APP_PACKAGE) else prefs[KEY_PREFERRED_MUSIC_APP_PACKAGE] = packageName
        }
    }

    /** Which registered [ApiProfileEntity] (providerType == PROVIDER_YOUTUBE_DATA_API) resolves the "play_music" tool's query to a specific YouTube Music video id — see YouTubeDataApiClient, DeviceMusicController.prepareOpenYoutubeMusicTrack. Null means none selected, in which case play_music falls back to the generic media-search intent. */
    val selectedYoutubeDataApiProfileId: Flow<Long?> =
        context.settingsDataStore.data.map { it[KEY_SELECTED_YOUTUBE_DATA_API_PROFILE_ID] }

    suspend fun currentSelectedYoutubeDataApiProfileId(): Long? = selectedYoutubeDataApiProfileId.first()

    suspend fun setSelectedYoutubeDataApiProfileId(id: Long?) {
        context.settingsDataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_SELECTED_YOUTUBE_DATA_API_PROFILE_ID) else prefs[KEY_SELECTED_YOUTUBE_DATA_API_PROFILE_ID] = id
        }
    }

    /** The active YouTube Data API profile's decrypted API key, or null when none is selected, the selected one was since deleted, or decryption fails. */
    suspend fun currentYoutubeDataApiKey(): String? {
        val id = currentSelectedYoutubeDataApiProfileId() ?: return null
        val profile = apiProfileDao.getById(id) ?: return null
        val ciphertext = profile.apiKeyCiphertext
        val iv = profile.apiKeyIv
        if (ciphertext == null || iv == null) return null
        return runCatching { cipher.decrypt(ciphertext, iv) }.getOrNull()
    }

    private fun resolve(selected: SelectedModel?): Flow<AppSettings> {
        if (selected == null) {
            return markdownEnabledFlow.map { markdownEnabled ->
                AppSettings(
                    apiKey = null,
                    model = AppSettings.DEFAULT_MODEL,
                    baseUrl = AppSettings.DEFAULT_BASE_URL,
                    markdownEnabled = markdownEnabled,
                )
            }
        }
        return apiProfileDao.observeById(selected.profileId).flatMapLatest { profile ->
            markdownEnabledFlow.map { markdownEnabled -> profile.toAppSettings(selected.model, markdownEnabled) }
        }
    }

    private fun Preferences.toSelectedModel(
        profileKey: Preferences.Key<Long>,
        modelKey: Preferences.Key<String>,
    ): SelectedModel? {
        val profileId = this[profileKey] ?: return null
        val model = this[modelKey] ?: return null
        return SelectedModel(profileId, model)
    }

    private fun ApiProfileEntity?.toAppSettings(model: String, markdownEnabled: Boolean): AppSettings {
        if (this == null) {
            return AppSettings(apiKey = null, model = model, baseUrl = AppSettings.DEFAULT_BASE_URL, markdownEnabled = markdownEnabled)
        }
        val ciphertext = apiKeyCiphertext
        val iv = apiKeyIv
        val apiKey = if (ciphertext != null && iv != null) {
            runCatching { cipher.decrypt(ciphertext, iv) }.getOrNull()
        } else {
            null
        }
        return AppSettings(apiKey = apiKey, model = model, baseUrl = baseUrl, markdownEnabled = markdownEnabled, profileName = name)
    }

    private companion object {
        val KEY_CHAT_PROFILE_ID = longPreferencesKey("chat_profile_id")
        val KEY_CHAT_MODEL = stringPreferencesKey("chat_model")
        val KEY_SYSTEM_PROFILE_ID = longPreferencesKey("system_profile_id")
        val KEY_SYSTEM_MODEL = stringPreferencesKey("system_model")
        val KEY_ASSISTANT_PROFILE_ID = longPreferencesKey("assistant_profile_id")
        val KEY_ASSISTANT_MODEL = stringPreferencesKey("assistant_model")
        val KEY_MARKDOWN_ENABLED = booleanPreferencesKey("markdown_enabled")
        val KEY_BRAVE_SEARCH_ENABLED = booleanPreferencesKey("brave_search_enabled")
        val KEY_SELECTED_WEB_SEARCH_PROFILE_ID = longPreferencesKey("selected_web_search_profile_id")
        val KEY_WEB_SEARCH_MAX_TOOL_ROUNDS = intPreferencesKey("web_search_max_tool_rounds")
        val KEY_SELECTED_SYSTEM_PROMPT_IDS = stringSetPreferencesKey("selected_system_prompt_ids")
        val KEY_LOCATION_ENABLED = booleanPreferencesKey("location_enabled")
        val KEY_SELECTED_TTS_PROFILE_ID = longPreferencesKey("selected_tts_profile_id")
        val KEY_ASSISTANT_TOOL_LAUNCH_TIMING = stringPreferencesKey("assistant_tool_launch_timing")
        val KEY_ALARM_TOOL_ENABLED = booleanPreferencesKey("alarm_tool_enabled")
        val KEY_NOTES_TOOL_ENABLED = booleanPreferencesKey("notes_tool_enabled")
        val KEY_PREFERRED_NOTE_APP_PACKAGE = stringPreferencesKey("preferred_note_app_package")
        val KEY_MESSAGING_TOOL_ENABLED = booleanPreferencesKey("messaging_tool_enabled")
        val KEY_PREFERRED_MESSAGING_APP_PACKAGE = stringPreferencesKey("preferred_messaging_app_package")
        val KEY_MUSIC_TOOL_ENABLED = booleanPreferencesKey("music_tool_enabled")
        val KEY_PREFERRED_MUSIC_APP_PACKAGE = stringPreferencesKey("preferred_music_app_package")
        val KEY_SELECTED_YOUTUBE_DATA_API_PROFILE_ID = longPreferencesKey("selected_youtube_data_api_profile_id")
        val KEY_WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val KEY_WAKE_WORD = stringPreferencesKey("wake_word")
        val KEY_SELECTED_STT_MODEL_ID = stringPreferencesKey("selected_stt_model_id")
    }
}
