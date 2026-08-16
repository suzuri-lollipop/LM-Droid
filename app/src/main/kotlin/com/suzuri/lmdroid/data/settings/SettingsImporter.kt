package com.suzuri.lmdroid.data.settings

import com.suzuri.lmdroid.data.db.ApiModelDao
import com.suzuri.lmdroid.data.db.ApiModelEntity
import com.suzuri.lmdroid.data.db.ApiProfileDao
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.db.SkillDao
import com.suzuri.lmdroid.data.db.SkillEntity
import com.suzuri.lmdroid.data.db.SystemPromptDao
import com.suzuri.lmdroid.data.db.SystemPromptEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.crypto.AEADBadTagException

/**
 * Restores a YAML document produced by [SettingsExporter] — the counterpart used by Settings →
 * 設定をインポート. API profiles, system prompts, and skills are always inserted as brand-new rows,
 * never overwriting or deleting whatever's already registered locally, since the exported ids only
 * ever made sense within that one export and may collide with unrelated local rows. A fresh id
 * mapping built during import re-points the imported model/system-prompt/skill selections at the
 * new rows. The
 * remaining, singular app-wide settings (markdown, Web検索, 位置情報, model selections) are applied
 * as an outright overwrite of whatever's currently set, since restoring a backup is expected to
 * reproduce the exported state exactly — except the chat model selection specifically falls back
 * to the first usable imported profile when the export's own selection is missing or unresolvable
 * (see [fallbackChatSelection]), since that one has no further fallback of its own the way
 * system/assistant selections fall back to it, and leaving it unset would strand an otherwise
 * successfully imported profile behind a chat screen that still reads as "not registered."
 *
 * Both of [SettingsExporter]'s modes are accepted transparently (see [isEncryptedSettingsExport]):
 *
 * - Plain YAML: API keys are restored the same way they were exported — the ciphertext + IV are
 *   copied verbatim, never decrypted here. This only decrypts successfully later if the importing
 *   device holds the same Android Keystore key used to encrypt it (i.e. this exact app install) —
 *   see [SettingsExporter]'s doc comment for why that's an intentional, accepted limitation.
 * - Password-protected: [password] decrypts the whole document first (a wrong password fails GCM
 *   authentication here, before anything is inserted — reported as [WrongPasswordException]), and
 *   each profile's plaintext key is then re-encrypted under THIS install's Keystore key via
 *   [ApiKeyCipher] before being stored, so the file's password is never persisted anywhere.
 */
class SettingsImporter(
    private val apiProfileDao: ApiProfileDao,
    private val apiModelDao: ApiModelDao,
    private val systemPromptDao: SystemPromptDao,
    private val skillDao: SkillDao,
    private val settingsRepository: SettingsRepository,
    private val apiKeyCipher: ApiKeyCipher,
    private val passwordCipher: PasswordSettingsCipher = PasswordSettingsCipher(),
) {
    suspend fun importFromYaml(yamlText: String, password: String? = null): Result<Unit> = runCatching {
        applyImport(decodeImport(yamlText, password))
    }

    private suspend fun decodeImport(yamlText: String, password: String?): SettingsExport {
        if (!isEncryptedSettingsExport(yamlText)) return decodeSettingsExportFromYaml(yamlText)
        require(!password.isNullOrEmpty()) { "This settings export is password-protected" }
        // PBKDF2 key derivation is deliberately CPU-expensive — keep it off the main thread.
        val innerYaml = withContext(Dispatchers.Default) {
            try {
                passwordCipher.decrypt(yamlText, password)
            } catch (e: AEADBadTagException) {
                throw WrongPasswordException()
            }
        }
        return decodeSettingsExportFromYaml(innerYaml)
    }

    private suspend fun applyImport(export: SettingsExport) {
        val profileIdMap = mutableMapOf<Long, Long>()
        val baseProfileTime = System.currentTimeMillis()
        export.apiProfiles.forEachIndexed { index, profile ->
            // A plaintext key (password-protected export) is re-encrypted under THIS install's
            // Keystore key; a Keystore ciphertext (plain export) is copied verbatim — see class doc.
            val reencryptedKey = profile.plainApiKey?.let { apiKeyCipher.encrypt(it) }
            val newId = apiProfileDao.insert(
                ApiProfileEntity(
                    name = profile.name,
                    providerType = profile.providerType,
                    apiKeyCiphertext = reencryptedKey?.ciphertextBase64 ?: profile.apiKey?.ciphertext,
                    apiKeyIv = reencryptedKey?.ivBase64 ?: profile.apiKey?.iv,
                    baseUrl = profile.baseUrl,
                    enabled = profile.enabled,
                    // Preserves the exported ordering (observeAll() sorts by createdAt) even if
                    // this loop runs fast enough for currentTimeMillis() to repeat.
                    createdAt = baseProfileTime + index,
                    voicevoxSpeakerId = profile.voicevoxSpeakerId,
                    openaiTtsModel = profile.openaiTtsModel,
                    openaiTtsVoice = profile.openaiTtsVoice,
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

        val skillIdMap = mutableMapOf<Long, Long>()
        val baseSkillTime = System.currentTimeMillis()
        export.skills.forEachIndexed { index, skill ->
            val newId = skillDao.insert(
                SkillEntity(name = skill.name, description = skill.description, content = skill.content, createdAt = baseSkillTime + index),
            )
            skillIdMap[skill.id] = newId
        }

        settingsRepository.saveMarkdownEnabled(export.markdownEnabled)
        settingsRepository.setSelectedSystemPromptIds(export.selectedSystemPromptIds.mapNotNull { promptIdMap[it] }.toSet())
        settingsRepository.setSelectedSkillIds(export.selectedSkillIds.mapNotNull { skillIdMap[it] }.toSet())

        settingsRepository.setBraveSearchEnabled(export.webSearch.enabled)
        settingsRepository.setSelectedWebSearchProfileId(export.webSearch.selectedProfileId?.let { profileIdMap[it] })
        settingsRepository.setWebSearchMaxToolRounds(export.webSearch.maxToolRounds)

        settingsRepository.setLocationEnabled(export.locationEnabled)

        settingsRepository.setSelectedTtsProfileId(export.tts.selectedProfileId?.let { profileIdMap[it] })

        settingsRepository.setAlarmToolEnabled(export.alarmToolEnabled)
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
