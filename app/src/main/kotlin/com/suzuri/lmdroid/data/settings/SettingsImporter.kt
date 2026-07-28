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
 * reproduce the exported state exactly.
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

        export.chatSelection?.let { selection ->
            profileIdMap[selection.profileId]?.let { newProfileId ->
                settingsRepository.setSelectedChatModel(newProfileId, selection.model)
            }
        }
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
