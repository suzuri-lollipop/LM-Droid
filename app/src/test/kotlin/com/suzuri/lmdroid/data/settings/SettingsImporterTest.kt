package com.suzuri.lmdroid.data.settings

import com.suzuri.lmdroid.data.db.ApiProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises only the pure YAML-decoding half of [SettingsImporter] (see [decodeSettingsExportFromYaml])
 * — the apply-to-repositories half needs a real [SettingsRepository], which needs a real
 * [ApiKeyCipher], which needs a live AndroidKeyStore that doesn't exist under Robolectric (see
 * [SettingsExporterTest]'s doc comment for the same constraint on the export side).
 */
class SettingsImporterTest {

    @Test
    fun `decodes a literal exported document back into the expected model`() {
        val yamlText = """
            exportedAt: "2026-07-27T00:00:00Z"
            apiProfiles:
              - id: 1
                name: "ローカルサーバー"
                baseUrl: "http://localhost:8080/v1"
                enabled: true
                apiKey:
                  ciphertext: "c1phertext=="
                  iv: "1v=="
                models:
                  - "gpt-4o-mini"
            chatSelection:
              profileId: 1
              profileName: "ローカルサーバー"
              model: "gpt-4o-mini"
            systemSelection: null
            markdownEnabled: true
            systemPrompts:
              - id: 1
                name: "敬語"
                content: "常に日本語の敬語で回答してください"
            selectedSystemPromptIds:
              - 1
            webSearch:
              enabled: true
              selectedProfileId: null
              maxToolRounds: 3
            locationEnabled: true
        """.trimIndent()

        val decoded = decodeSettingsExportFromYaml(yamlText)

        assertEquals(1, decoded.apiProfiles.size)
        assertEquals("ローカルサーバー", decoded.apiProfiles[0].name)
        // Not present in the literal YAML above — confirms an export from before providerType
        // existed still decodes cleanly, defaulting to the (formerly sole) LLM provider type.
        assertEquals(ApiProfileEntity.PROVIDER_OPENAI_COMPATIBLE, decoded.apiProfiles[0].providerType)
        assertEquals("c1phertext==", decoded.apiProfiles[0].apiKey?.ciphertext)
        assertEquals(listOf("gpt-4o-mini"), decoded.apiProfiles[0].models)
        assertEquals(listOf(1L), decoded.selectedSystemPromptIds)
        assertEquals("敬語", decoded.systemPrompts.single().name)
        assertEquals(3, decoded.webSearch.maxToolRounds)
        // Neither voicevoxSpeakerId nor the whole tts section exists in the literal YAML above —
        // confirms an export from before the TTS feature existed still decodes cleanly, defaulting
        // to "no speaker id"/"no tts profile selected" (i.e. this device's own on-device speech).
        assertEquals(null, decoded.apiProfiles[0].voicevoxSpeakerId)
        assertEquals(null, decoded.tts.selectedProfileId)
        // Not present in the literal YAML above either — confirms an export from before the
        // alarm/timer tools existed still decodes cleanly, defaulting to "not offered to the model".
        assertEquals(false, decoded.alarmToolEnabled)
    }

    @Test
    fun `decodes the inner payload shape of a password-protected export, carrying plaintext keys`() {
        // What SettingsImporter sees after PasswordSettingsCipher.decrypt — the same wire format,
        // but keys travel in plainApiKey (re-encrypted under the local Keystore key on apply).
        val yamlText = """
            exportedAt: "2026-08-15T00:00:00Z"
            apiProfiles:
              - id: 1
                name: "ローカルサーバー"
                baseUrl: "http://localhost:8080/v1"
                enabled: true
                plainApiKey: "sk-live-秘密のキー"
                models:
                  - "gpt-4o-mini"
            markdownEnabled: true
        """.trimIndent()

        val decoded = decodeSettingsExportFromYaml(yamlText)

        assertEquals("sk-live-秘密のキー", decoded.apiProfiles[0].plainApiKey)
        assertEquals(null, decoded.apiProfiles[0].apiKey)
    }

    @Test
    fun `round-trips through encode back to an equal SettingsExport`() {
        val original = SettingsExport(
            exportedAt = "2026-07-27T00:00:00Z",
            apiProfiles = listOf(
                ExportedApiProfile(
                    id = 1,
                    name = "ローカルサーバー",
                    baseUrl = "http://localhost:8080/v1",
                    enabled = true,
                    apiKey = ExportedEncryptedValue(ciphertext = "c1phertext==", iv = "1v=="),
                    models = listOf("gpt-4o-mini"),
                ),
                ExportedApiProfile(
                    id = 2,
                    name = "VOICEVOX",
                    providerType = ApiProfileEntity.PROVIDER_VOICEVOX_COMPATIBLE,
                    baseUrl = "http://127.0.0.1:50021",
                    enabled = true,
                    apiKey = null,
                    models = emptyList(),
                    voicevoxSpeakerId = 3,
                ),
            ),
            chatSelection = ExportedModelSelection(profileId = 1, profileName = "ローカルサーバー", model = "gpt-4o-mini"),
            systemSelection = null,
            assistantSelection = ExportedModelSelection(profileId = 1, profileName = "ローカルサーバー", model = "gpt-4o"),
            markdownEnabled = true,
            systemPrompts = listOf(ExportedSystemPrompt(id = 1, name = "敬語", content = "常に日本語の敬語で回答してください")),
            selectedSystemPromptIds = listOf(1),
            webSearch = ExportedWebSearchSettings(enabled = true, selectedProfileId = null, maxToolRounds = 3),
            locationEnabled = true,
            tts = ExportedTtsSettings(selectedProfileId = 2),
            alarmToolEnabled = true,
        )

        val decoded = decodeSettingsExportFromYaml(encodeSettingsExportToYaml(original))

        assertEquals(original, decoded)
        assertEquals(2L, decoded.tts.selectedProfileId)
        assertEquals(3, decoded.apiProfiles[1].voicevoxSpeakerId)
        assertEquals("gpt-4o", decoded.assistantSelection?.model)
        assertEquals(true, decoded.alarmToolEnabled)
    }

    @Test
    fun `decodes an export missing the entire webSearch section, from before Web検索 existed`() {
        val yamlText = """
            exportedAt: "2025-01-01T00:00:00Z"
            apiProfiles:
              - id: 1
                name: "ローカルサーバー"
                baseUrl: "http://localhost:8080/v1"
                enabled: true
                models:
                  - "gpt-4o-mini"
            markdownEnabled: true
        """.trimIndent()

        val decoded = decodeSettingsExportFromYaml(yamlText)

        assertEquals(ExportedWebSearchSettings(), decoded.webSearch)
        assertEquals(false, decoded.webSearch.enabled)
        assertEquals(null, decoded.assistantSelection)
        assertEquals(1, decoded.apiProfiles.size)
    }

    @Test
    fun `ignores unrecognized fields left over from an older schema instead of failing the whole import`() {
        val yamlText = """
            exportedAt: "2025-06-01T00:00:00Z"
            apiProfiles:
              - id: 1
                name: "ローカルサーバー"
                baseUrl: "http://localhost:8080/v1"
                enabled: true
                models: []
            markdownEnabled: true
            webSearch:
              enabled: true
              apiKey:
                ciphertext: "old-c1phertext=="
                iv: "old-1v=="
              maxToolRounds: 5
            someRemovedFeature:
              nested: true
        """.trimIndent()

        val decoded = decodeSettingsExportFromYaml(yamlText)

        // The old raw apiKey field (from before Web検索 became profile-based) and the unrelated
        // unknown top-level key are both silently ignored rather than aborting the whole decode —
        // everything else in this same document still comes through correctly.
        assertEquals(true, decoded.webSearch.enabled)
        assertEquals(5, decoded.webSearch.maxToolRounds)
        assertEquals(null, decoded.webSearch.selectedProfileId)
        assertEquals("ローカルサーバー", decoded.apiProfiles.single().name)
    }

    @Test
    fun `fallbackChatSelection picks the first enabled model-bearing OpenAI-compatible profile`() {
        val apiProfiles = listOf(
            ExportedApiProfile(
                id = 1,
                name = "Brave Search",
                providerType = ApiProfileEntity.PROVIDER_BRAVE_SEARCH,
                baseUrl = "https://api.search.brave.com",
                enabled = true,
                models = emptyList(),
            ),
            ExportedApiProfile(
                id = 2,
                name = "ローカルサーバー",
                baseUrl = "http://localhost:8080/v1",
                enabled = true,
                models = listOf("gpt-4o-mini", "gpt-4o"),
            ),
        )
        val profileIdMap = mapOf(1L to 101L, 2L to 102L)

        val selection = fallbackChatSelection(apiProfiles, profileIdMap)

        assertEquals(SelectedModel(profileId = 102L, model = "gpt-4o-mini"), selection)
    }

    @Test
    fun `fallbackChatSelection skips a disabled profile even if it has models`() {
        val apiProfiles = listOf(
            ExportedApiProfile(id = 1, name = "無効化済み", baseUrl = "http://localhost:8080/v1", enabled = false, models = listOf("gpt-4o-mini")),
            ExportedApiProfile(id = 2, name = "有効", baseUrl = "http://localhost:8081/v1", enabled = true, models = listOf("gpt-4o")),
        )
        val profileIdMap = mapOf(1L to 101L, 2L to 102L)

        val selection = fallbackChatSelection(apiProfiles, profileIdMap)

        assertEquals(SelectedModel(profileId = 102L, model = "gpt-4o"), selection)
    }

    @Test
    fun `fallbackChatSelection returns null when nothing usable was imported`() {
        val apiProfiles = listOf(
            ExportedApiProfile(
                id = 1,
                name = "VOICEVOX",
                providerType = ApiProfileEntity.PROVIDER_VOICEVOX_COMPATIBLE,
                baseUrl = "http://127.0.0.1:50021",
                enabled = true,
                models = emptyList(),
            ),
            ExportedApiProfile(id = 2, name = "モデル未取得", baseUrl = "http://localhost:8080/v1", enabled = true, models = emptyList()),
        )
        val profileIdMap = mapOf(1L to 101L, 2L to 102L)

        assertEquals(null, fallbackChatSelection(apiProfiles, profileIdMap))
    }
}
