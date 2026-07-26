package com.suzuri.lmdroid.data.settings

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.suzuri.lmdroid.data.db.ApiModelDao
import com.suzuri.lmdroid.data.db.ApiProfileDao
import com.suzuri.lmdroid.data.db.SystemPromptDao
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Serializes every user-configurable setting (API profiles + their registered models, chat/system
 * model selections, markdown preference, system prompt profiles, and Web検索 settings) into a
 * single YAML document — used by Settings → 設定をエクスポート for backup/inspection purposes.
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
                baseUrl = profile.baseUrl,
                enabled = profile.enabled,
                apiKey = exportedEncryptedValueOf(profile.apiKeyCiphertext, profile.apiKeyIv),
                models = models.map { it.modelId },
            )
        }
        val profileNameById = profiles.associate { it.id to it.name }

        val chatSelection = settingsRepository.selectedChatModel.first()
        val systemSelection = settingsRepository.selectedSystemModel.first()
        val braveSearchKey = settingsRepository.currentBraveSearchApiKeyEncrypted()

        val systemPrompts = systemPromptDao.observeAll().first()

        return SettingsExport(
            exportedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            apiProfiles = exportedProfiles,
            chatSelection = chatSelection?.toExported(profileNameById),
            systemSelection = systemSelection?.toExported(profileNameById),
            markdownEnabled = settingsRepository.currentChatSettings().markdownEnabled,
            systemPrompts = systemPrompts.map { ExportedSystemPrompt(id = it.id, name = it.name, content = it.content) },
            selectedSystemPromptIds = settingsRepository.currentSelectedSystemPromptIds().sorted(),
            webSearch = ExportedWebSearchSettings(
                enabled = settingsRepository.currentBraveSearchEnabled(),
                apiKey = braveSearchKey?.let { ExportedEncryptedValue(it.ciphertextBase64, it.ivBase64) },
                maxToolRounds = settingsRepository.currentWebSearchMaxToolRounds(),
            ),
            locationEnabled = settingsRepository.currentLocationEnabled(),
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

val settingsExportYaml: Yaml = Yaml(configuration = YamlConfiguration(encodeDefaults = true))

@Serializable
data class SettingsExport(
    val exportedAt: String,
    val apiProfiles: List<ExportedApiProfile>,
    val chatSelection: ExportedModelSelection? = null,
    val systemSelection: ExportedModelSelection? = null,
    val markdownEnabled: Boolean,
    val systemPrompts: List<ExportedSystemPrompt> = emptyList(),
    val selectedSystemPromptIds: List<Long> = emptyList(),
    val webSearch: ExportedWebSearchSettings,
    val locationEnabled: Boolean = false,
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
    val baseUrl: String,
    val enabled: Boolean,
    val apiKey: ExportedEncryptedValue? = null,
    val models: List<String>,
)

@Serializable
data class ExportedModelSelection(
    val profileId: Long,
    val profileName: String? = null,
    val model: String,
)

@Serializable
data class ExportedWebSearchSettings(
    val enabled: Boolean,
    val apiKey: ExportedEncryptedValue? = null,
    val maxToolRounds: Int,
)

/** AES-256-GCM ciphertext + IV, both base64 — see [ApiKeyCipher]. Decryptable only via this exact app install's Android Keystore key. */
@Serializable
data class ExportedEncryptedValue(
    val ciphertext: String,
    val iv: String,
)
