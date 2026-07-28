package com.suzuri.lmdroid.data.settings

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.suzuri.lmdroid.data.db.ApiModelDao
import com.suzuri.lmdroid.data.db.ApiProfileDao
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.db.SystemPromptDao
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Serializes every user-configurable setting (API profiles + their registered models, chat/system/
 * アシスタント model selections, markdown preference, system prompt profiles, and Web検索/音声
 * settings) into a single YAML document — used by Settings → 設定をエクスポート for backup/inspection
 * purposes.
 *
 * API keys are never written in plaintext: each field is copied exactly as it's already stored —
 * AES-256-GCM ciphertext + IV under [ApiKeyCipher]'s Android Keystore key (non-exportable, device-
 * bound) — rather than decrypted and re-encrypted here, so the plaintext key never needs to exist
 * in memory during export. This also means decrypting the resulting YAML's keys is only possible
 * from this exact app install on this exact device; the export is a local backup/audit artifact,
 * not a file meant to carry credentials to a different device or a reinstalled app.
 */
class SettingsExporter(
    private val apiProfileDao: ApiProfileDao,
    private val apiModelDao: ApiModelDao,
    private val systemPromptDao: SystemPromptDao,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun exportToYaml(): String = encodeSettingsExportToYaml(buildExport())

    /** Gathers the current settings into one snapshot — split out from the (pure, unit-testable) YAML encoding in [encodeSettingsExportToYaml]. */
    private suspend fun buildExport(): SettingsExport {
        val profiles = apiProfileDao.observeAll().first()
        val exportedProfiles = profiles.map { profile ->
            val models = apiModelDao.observeByProfile(profile.id).first()
            ExportedApiProfile(
                id = profile.id,
                name = profile.name,
                providerType = profile.providerType,
                baseUrl = profile.baseUrl,
                enabled = profile.enabled,
                apiKey = exportedEncryptedValueOf(profile.apiKeyCiphertext, profile.apiKeyIv),
                models = models.map { it.modelId },
                voicevoxSpeakerId = profile.voicevoxSpeakerId,
            )
        }
        val profileNameById = profiles.associate { it.id to it.name }

        val chatSelection = settingsRepository.selectedChatModel.first()
        val systemSelection = settingsRepository.selectedSystemModel.first()
        val assistantSelection = settingsRepository.selectedAssistantModel.first()

        val systemPrompts = systemPromptDao.observeAll().first()

        return SettingsExport(
            exportedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            apiProfiles = exportedProfiles,
            chatSelection = chatSelection?.toExported(profileNameById),
            systemSelection = systemSelection?.toExported(profileNameById),
            assistantSelection = assistantSelection?.toExported(profileNameById),
            markdownEnabled = settingsRepository.currentChatSettings().markdownEnabled,
            systemPrompts = systemPrompts.map { ExportedSystemPrompt(id = it.id, name = it.name, content = it.content) },
            selectedSystemPromptIds = settingsRepository.currentSelectedSystemPromptIds().sorted(),
            webSearch = ExportedWebSearchSettings(
                enabled = settingsRepository.currentBraveSearchEnabled(),
                selectedProfileId = settingsRepository.currentSelectedWebSearchProfileId(),
                maxToolRounds = settingsRepository.currentWebSearchMaxToolRounds(),
            ),
            locationEnabled = settingsRepository.currentLocationEnabled(),
            tts = ExportedTtsSettings(selectedProfileId = settingsRepository.currentSelectedTtsProfileId()),
        )
    }

    private fun SelectedModel.toExported(profileNameById: Map<Long, String>) =
        ExportedModelSelection(profileId = profileId, profileName = profileNameById[profileId], model = model)

    private fun exportedEncryptedValueOf(ciphertext: String?, iv: String?): ExportedEncryptedValue? {
        if (ciphertext == null || iv == null) return null
        return ExportedEncryptedValue(ciphertext, iv)
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
    val systemPrompts: List<ExportedSystemPrompt> = emptyList(),
    val selectedSystemPromptIds: List<Long> = emptyList(),
    // Defaulted so an export from before Web検索 existed at all (which has no webSearch section
    // whatsoever, not just an outdated shape of one) still decodes instead of failing on a
    // "required field missing" error.
    val webSearch: ExportedWebSearchSettings = ExportedWebSearchSettings(),
    val locationEnabled: Boolean = false,
    val tts: ExportedTtsSettings = ExportedTtsSettings(),
)

@Serializable
data class ExportedSystemPrompt(
    val id: Long,
    val name: String,
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
    val models: List<String>,
    // Only meaningful for PROVIDER_VOICEVOX_COMPATIBLE.
    val voicevoxSpeakerId: Int? = null,
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
