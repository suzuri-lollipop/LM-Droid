package com.suzuri.lmdroid.data.settings

import com.suzuri.lmdroid.data.db.ApiModelDao
import com.suzuri.lmdroid.data.db.ApiModelEntity
import com.suzuri.lmdroid.data.db.ApiProfileDao
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.db.SystemPromptDao
import com.suzuri.lmdroid.data.db.SystemPromptEntity

/**
 * Restores a YAML document produced by [SettingsExporter] — the counterpart used by Settings →
 * 設定をインポート. API profiles and system prompts are always inserted as brand-new rows, never
 * overwriting or deleting whatever's already registered locally, since the exported ids only ever
 * made sense within that one export and may collide with unrelated local rows. A fresh id mapping
 * built during import re-points the imported model/system-prompt selections at the new rows. The
 * remaining, singular app-wide settings (markdown, Web検索, 位置情報, model selections) are applied
 * as an outright overwrite of whatever's currently set, since restoring a backup is expected to
 * reproduce the exported state exactly — except the chat model selection specifically falls back
 * to the first usable imported profile when the export's own selection is missing or unresolvable
 * (see [fallbackChatSelection]), since that one has no further fallback of its own the way
 * system/assistant selections fall back to it, and leaving it unset would strand an otherwise
 * successfully imported profile behind a chat screen that still reads as "not registered."
 *
 * API keys are restored the same way they were exported: the ciphertext + IV are copied verbatim,
 * never decrypted here. This only decrypts successfully later if the importing device holds the
 * same Android Keystore key used to encrypt it (i.e. this exact app install) — see
 * [SettingsExporter]'s doc comment for why that's an intentional, accepted limitation rather than
 * a bug.
 */
class SettingsImporter(
    private val apiProfileDao: ApiProfileDao,
    private val apiModelDao: ApiModelDao,
    private val systemPromptDao: SystemPromptDao,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun importFromYaml(yamlText: String): Result<Unit> = runCatching {
        applyImport(decodeSettingsExportFromYaml(yamlText))
    }

    private suspend fun applyImport(export: SettingsExport) {
        val profileIdMap = mutableMapOf<Long, Long>()
        val baseProfileTime = System.currentTimeMillis()
        export.apiProfiles.forEachIndexed { index, profile ->
            val newId = apiProfileDao.insert(
                ApiProfileEntity(
                    name = profile.name,
                    providerType = profile.providerType,
                    apiKeyCiphertext = profile.apiKey?.ciphertext,
                    apiKeyIv = profile.apiKey?.iv,
                    baseUrl = profile.baseUrl,
                    enabled = profile.enabled,
                    // Preserves the exported ordering (observeAll() sorts by createdAt) even if
                    // this loop runs fast enough for currentTimeMillis() to repeat.
                    createdAt = baseProfileTime + index,
                    voicevoxSpeakerId = profile.voicevoxSpeakerId,
                ),
            )
            profileIdMap[profile.id] = newId
            if (profile.models.isNotEmpty()) {
                apiModelDao.insertAll(profile.models.map { modelId -> ApiModelEntity(profileId = newId, modelId = modelId) })
            }
        }

        val promptIdMap = mutableMapOf<Long, Long>()
        val basePromptTime = System.currentTimeMillis()
        export.systemPrompts.forEachIndexed { index, prompt ->
            val newId = systemPromptDao.insert(
                SystemPromptEntity(name = prompt.name, content = prompt.content, createdAt = basePromptTime + index),
            )
            promptIdMap[prompt.id] = newId
        }

        // Unlike systemSelection/assistantSelection (which are meant to fall back to chatSelection
        // when unset), there's nothing left for chatSelection itself to fall back to — if it's
        // missing, or its profile reference doesn't resolve (e.g. an old export whose chatSelection
        // predates a later schema change), leaving nothing selected would show "APIキー未登録" on
        // the chat screen even though a perfectly usable profile was just imported. So this picks
        // something usable from what was actually imported rather than leaving chat unusable.
        val resolvedChatSelection = export.chatSelection
            ?.let { selection -> profileIdMap[selection.profileId]?.let { SelectedModel(it, selection.model) } }
            ?: fallbackChatSelection(export.apiProfiles, profileIdMap)
        resolvedChatSelection?.let { settingsRepository.setSelectedChatModel(it.profileId, it.model) }

        val systemSelection = export.systemSelection
        if (systemSelection != null) {
            profileIdMap[systemSelection.profileId]?.let { newProfileId ->
                settingsRepository.setSelectedSystemModel(newProfileId, systemSelection.model)
            }
        } else {
            settingsRepository.setSelectedSystemModel(null, null)
        }

        val assistantSelection = export.assistantSelection
        if (assistantSelection != null) {
            profileIdMap[assistantSelection.profileId]?.let { newProfileId ->
                settingsRepository.setSelectedAssistantModel(newProfileId, assistantSelection.model)
            }
        } else {
            settingsRepository.setSelectedAssistantModel(null, null)
        }

        settingsRepository.saveMarkdownEnabled(export.markdownEnabled)
        settingsRepository.setSelectedSystemPromptIds(export.selectedSystemPromptIds.mapNotNull { promptIdMap[it] }.toSet())

        settingsRepository.setBraveSearchEnabled(export.webSearch.enabled)
        settingsRepository.setSelectedWebSearchProfileId(export.webSearch.selectedProfileId?.let { profileIdMap[it] })
        settingsRepository.setWebSearchMaxToolRounds(export.webSearch.maxToolRounds)

        settingsRepository.setLocationEnabled(export.locationEnabled)

        settingsRepository.setSelectedTtsProfileId(export.tts.selectedProfileId?.let { profileIdMap[it] })
    }
}

/**
 * The read half of [SettingsExporter]'s wire format — kept as a free function alongside
 * [encodeSettingsExportToYaml] so decoding is unit-testable without needing a real
 * [SettingsRepository]/[ApiKeyCipher] (see [encodeSettingsExportToYaml]'s doc comment).
 */
fun decodeSettingsExportFromYaml(yamlText: String): SettingsExport =
    settingsExportYaml.decodeFromString(SettingsExport.serializer(), yamlText)

/**
 * The first enabled, model-bearing OpenAI-compatible profile that was actually imported — a
 * reasonable default chat selection when the export's own chatSelection is missing or doesn't
 * resolve through [profileIdMap] (see [SettingsImporter]'s doc comment for why chatSelection
 * specifically needs this fallback). Kept as a free function, like [decodeSettingsExportFromYaml],
 * so this is unit-testable without needing a real [SettingsRepository].
 */
fun fallbackChatSelection(apiProfiles: List<ExportedApiProfile>, profileIdMap: Map<Long, Long>): SelectedModel? {
    val profile = apiProfiles.firstOrNull { profile ->
        profile.providerType == ApiProfileEntity.PROVIDER_OPENAI_COMPATIBLE && profile.enabled && profile.models.isNotEmpty()
    } ?: return null
    val newProfileId = profileIdMap[profile.id] ?: return null
    return SelectedModel(profileId = newProfileId, model = profile.models.first())
}
