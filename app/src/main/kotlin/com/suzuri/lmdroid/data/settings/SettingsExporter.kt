package com.suzuri.lmdroid.data.settings

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.suzuri.lmdroid.data.db.ApiModelDao
import com.suzuri.lmdroid.data.db.ApiProfileDao
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.db.SkillDao
import com.suzuri.lmdroid.data.db.SystemPromptDao
import com.suzuri.lmdroid.data.db.ThinkingEffort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Serializes every user-configurable setting (API profiles + their registered models, chat/system/
 * アシスタント model selections, markdown preference, system prompt profiles, skills, and Web検索/
 * 音声 settings) into a single YAML document — used by Settings → 設定をエクスポート for backup/
 * inspection purposes.
 *
 * Two modes, chosen by whether the user supplies a password:
 *
 * - [exportToYaml] (no password): API keys are never written in plaintext — each field is copied
 *   exactly as it's already stored, AES-256-GCM ciphertext + IV under [ApiKeyCipher]'s Android
 *   Keystore key (non-exportable, device-bound), so the plaintext key never needs to exist in
 *   memory during export. This also means decrypting the resulting YAML's keys is only possible
 *   from this exact app install on this exact device; the export is a local backup/audit artifact,
 *   not a file meant to carry credentials to a different device or a reinstalled app.
 * - [exportToEncryptedYaml] (password supplied): the keys ARE the point — each stored key is
 *   decrypted via [ApiKeyCipher], carried as [ExportedApiProfile.plainApiKey] inside the document,
 *   and the entire YAML is then encrypted under the password via [PasswordSettingsCipher]
 *   (PBKDF2 → AES-256-GCM). The plaintext exists only transiently, inside the process, and never
 *   touches disk. That makes the file safe to carry to another device or a reinstalled app, where
 *   [SettingsImporter] asks for the same password.
 */
class SettingsExporter(
    private val apiProfileDao: ApiProfileDao,
    private val apiModelDao: ApiModelDao,
    private val systemPromptDao: SystemPromptDao,
    private val skillDao: SkillDao,
    private val settingsRepository: SettingsRepository,
    private val apiKeyCipher: ApiKeyCipher,
    private val passwordCipher: PasswordSettingsCipher = PasswordSettingsCipher(),
) {
    suspend fun exportToYaml(): String = encodeSettingsExportToYaml(buildExport(includePlainKeys = false))

    /** Password-protected variant of [exportToYaml] — API keys included, whole document encrypted under [password] (see class doc). */
    suspend fun exportToEncryptedYaml(password: String): String {
        val innerYaml = encodeSettingsExportToYaml(buildExport(includePlainKeys = true))
        // PBKDF2 key derivation is deliberately CPU-expensive — keep it off the main thread.
        return withContext(Dispatchers.Default) { passwordCipher.encrypt(innerYaml, password) }
    }

    /** Gathers the current settings into one snapshot — split out from the (pure, unit-testable) YAML encoding in [encodeSettingsExportToYaml]. */
    private suspend fun buildExport(includePlainKeys: Boolean): SettingsExport {
        val profiles = apiProfileDao.observeAll().first()
        val exportedProfiles = profiles.map { profile ->
            val models = apiModelDao.observeByProfile(profile.id).first()
            ExportedApiProfile(
                id = profile.id,
                name = profile.name,
                providerType = profile.providerType,
                baseUrl = profile.baseUrl,
                enabled = profile.enabled,
                // Exactly one of the two is ever set: the Keystore ciphertext copy (plain export)
                // or the decrypted key (password-protected export) — see the class doc.
                apiKey = if (includePlainKeys) null else exportedEncryptedValueOf(profile.apiKeyCiphertext, profile.apiKeyIv),
                plainApiKey = if (includePlainKeys) decryptedKeyOf(profile.apiKeyCiphertext, profile.apiKeyIv) else null,
                models = models.map { it.modelId },
                voicevoxSpeakerId = profile.voicevoxSpeakerId,
                openaiTtsModel = profile.openaiTtsModel,
                openaiTtsVoice = profile.openaiTtsVoice,
                defaultThinkingEffort = profile.defaultThinkingEffort,
                defaultMemoryEnabled = profile.defaultMemoryEnabled,
            )
        }
        val profileNameById = profiles.associate { it.id to it.name }

        val chatSelection = settingsRepository.selectedChatModel.first()
        val systemSelection = settingsRepository.selectedSystemModel.first()
        val assistantSelection = settingsRepository.selectedAssistantModel.first()

        val systemPrompts = systemPromptDao.observeAll().first()
        val skills = skillDao.observeAll().first()
        val chatSettings = settingsRepository.currentChatSettings()

        return SettingsExport(
            exportedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            apiProfiles = exportedProfiles,
            chatSelection = chatSelection?.toExported(profileNameById),
            systemSelection = systemSelection?.toExported(profileNameById),
            assistantSelection = assistantSelection?.toExported(profileNameById),
            markdownEnabled = chatSettings.markdownEnabled,
            thinkingEffort = chatSettings.thinkingEffort,
            memoryEnabled = chatSettings.memoryEnabled,
            systemPrompts = systemPrompts.map { ExportedSystemPrompt(id = it.id, name = it.name, content = it.content) },
            selectedSystemPromptIds = settingsRepository.currentSelectedSystemPromptIds().sorted(),
            skills = skills.map { ExportedSkill(id = it.id, name = it.name, description = it.description, content = it.content) },
            selectedSkillIds = settingsRepository.currentSelectedSkillIds().sorted(),
            webSearch = ExportedWebSearchSettings(
                enabled = settingsRepository.currentBraveSearchEnabled(),
                selectedProfileId = settingsRepository.currentSelectedWebSearchProfileId(),
                maxToolRounds = settingsRepository.currentWebSearchMaxToolRounds(),
            ),
            locationEnabled = settingsRepository.currentLocationEnabled(),
            tts = ExportedTtsSettings(selectedProfileId = settingsRepository.currentSelectedTtsProfileId()),
            alarmToolEnabled = settingsRepository.currentAlarmToolEnabled(),
        )
    }

    private fun SelectedModel.toExported(profileNameById: Map<Long, String>) =
        ExportedModelSelection(profileId = profileId, profileName = profileNameById[profileId], model = model)

    private fun exportedEncryptedValueOf(ciphertext: String?, iv: String?): ExportedEncryptedValue? {
        if (ciphertext == null || iv == null) return null
        return ExportedEncryptedValue(ciphertext, iv)
    }

    /**
     * The plaintext key for a password-protected export — null when no key is stored, or when
     * the stored ciphertext no longer decrypts (e.g. the Keystore key was lost). The latter drops
     * that one key rather than failing the entire export, since the ciphertext was already
     * unusable on this device anyway.
     */
    private fun decryptedKeyOf(ciphertext: String?, iv: String?): String? {
        if (ciphertext == null || iv == null) return null
        return runCatching { apiKeyCipher.decrypt(ciphertext, iv) }.getOrNull()
    }
}

/**
 * The wire format used by [SettingsExporter.exportToYaml] — kept as a free function on the plain
 * [SettingsExport] data model (rather than a private detail of [SettingsExporter]) so it can be
 * unit tested directly, without needing a real [SettingsRepository]/[ApiKeyCipher] (which requires
 * a live AndroidKeyStore and isn't constructible under Robolectric).
 */
fun encodeSettingsExportToYaml(export: SettingsExport): String =
    settingsExportYaml.encodeToString(SettingsExport.serializer(), export)

val settingsExportYaml: Yaml = Yaml(
    configuration = YamlConfiguration(
        encodeDefaults = true,
        // A past export won't have every field this version knows about (features added since),
        // and one written by a future/different version may have fields this version no longer
        // recognizes at all (e.g. Web検索 used to carry a raw apiKey before it became a profile
        // pointer) — strict mode would reject the WHOLE document over a single unrecognized key.
        // Off, so decoding imports whatever fields are actually present/still understood and
        // quietly defaults the rest, rather than failing the entire import over a partial mismatch.
        strictMode = false,
    ),
)

@Serializable
data class SettingsExport(
    val exportedAt: String,
    val apiProfiles: List<ExportedApiProfile>,
    val chatSelection: ExportedModelSelection? = null,
    val systemSelection: ExportedModelSelection? = null,
    val assistantSelection: ExportedModelSelection? = null,
    val markdownEnabled: Boolean,
    // Defaulted (unlike markdownEnabled) so an export from before this selector existed still
    // decodes instead of failing on a "required field missing" error.
    val thinkingEffort: ThinkingEffort = ThinkingEffort.MEDIUM,
    val memoryEnabled: Boolean = true,
    val systemPrompts: List<ExportedSystemPrompt> = emptyList(),
    val selectedSystemPromptIds: List<Long> = emptyList(),
    val skills: List<ExportedSkill> = emptyList(),
    val selectedSkillIds: List<Long> = emptyList(),
    // Defaulted so an export from before Web検索 existed at all (which has no webSearch section
    // whatsoever, not just an outdated shape of one) still decodes instead of failing on a
    // "required field missing" error.
    val webSearch: ExportedWebSearchSettings = ExportedWebSearchSettings(),
    val locationEnabled: Boolean = false,
    val tts: ExportedTtsSettings = ExportedTtsSettings(),
    val alarmToolEnabled: Boolean = false,
)

@Serializable
data class ExportedSystemPrompt(
    val id: Long,
    val name: String,
    val content: String,
)

@Serializable
data class ExportedSkill(
    val id: Long,
    val name: String,
    val description: String,
    val content: String,
)

@Serializable
data class ExportedApiProfile(
    val id: Long,
    val name: String,
    // Defaults to the (formerly sole) LLM provider type so a YAML exported before this field
    // existed still decodes cleanly.
    val providerType: String = ApiProfileEntity.PROVIDER_OPENAI_COMPATIBLE,
    val baseUrl: String,
    val enabled: Boolean,
    val apiKey: ExportedEncryptedValue? = null,
    // The decrypted key — only ever populated inside the (whole-document encrypted) payload of a
    // password-protected export, never in a plain YAML file. NEVER-encoded so plain exports stay
    // byte-identical to the pre-password schema instead of growing a `plainApiKey: null` per profile.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val plainApiKey: String? = null,
    val models: List<String>,
    // Only meaningful for PROVIDER_VOICEVOX_COMPATIBLE.
    val voicevoxSpeakerId: Int? = null,
    // Only meaningful for PROVIDER_OPENAI_TTS.
    val openaiTtsModel: String? = null,
    val openaiTtsVoice: String? = null,
    // Only meaningful for PROVIDER_OPENAI_COMPATIBLE — see ApiProfileEntity.defaultThinkingEffort.
    val defaultThinkingEffort: ThinkingEffort? = null,
    // Only meaningful for PROVIDER_OPENAI_COMPATIBLE — see ApiProfileEntity.defaultMemoryEnabled.
    val defaultMemoryEnabled: Boolean? = null,
)

@Serializable
data class ExportedModelSelection(
    val profileId: Long,
    val profileName: String? = null,
    val model: String,
)

@Serializable
data class ExportedWebSearchSettings(
    val enabled: Boolean = false,
    // Points at one of apiProfiles above (providerType == PROVIDER_BRAVE_SEARCH) — the key itself
    // travels with that profile's own apiKey field, not duplicated here.
    val selectedProfileId: Long? = null,
    val maxToolRounds: Int = 0,
)

@Serializable
data class ExportedTtsSettings(
    // Points at one of apiProfiles above (providerType == PROVIDER_VOICEVOX_COMPATIBLE) — null
    // means the assistant overlay speaks with this device's own built-in text-to-speech.
    val selectedProfileId: Long? = null,
)

/** AES-256-GCM ciphertext + IV, both base64 — see [ApiKeyCipher]. Decryptable only via this exact app install's Android Keystore key. */
@Serializable
data class ExportedEncryptedValue(
    val ciphertext: String,
    val iv: String,
)
