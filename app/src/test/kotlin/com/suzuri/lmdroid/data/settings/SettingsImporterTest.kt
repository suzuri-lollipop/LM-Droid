package com.suzuri.lmdroid.data.settings

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
              apiKey: null
              maxToolRounds: 3
            locationEnabled: true
        """.trimIndent()

        val decoded = decodeSettingsExportFromYaml(yamlText)

        assertEquals(1, decoded.apiProfiles.size)
        assertEquals("ローカルサーバー", decoded.apiProfiles[0].name)
        assertEquals("c1phertext==", decoded.apiProfiles[0].apiKey?.ciphertext)
        assertEquals(listOf("gpt-4o-mini"), decoded.apiProfiles[0].models)
        assertEquals(listOf(1L), decoded.selectedSystemPromptIds)
        assertEquals("敬語", decoded.systemPrompts.single().name)
        assertEquals(3, decoded.webSearch.maxToolRounds)
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
            ),
            chatSelection = ExportedModelSelection(profileId = 1, profileName = "ローカルサーバー", model = "gpt-4o-mini"),
            systemSelection = null,
            markdownEnabled = true,
            systemPrompts = listOf(ExportedSystemPrompt(id = 1, name = "敬語", content = "常に日本語の敬語で回答してください")),
            selectedSystemPromptIds = listOf(1),
            webSearch = ExportedWebSearchSettings(enabled = true, apiKey = null, maxToolRounds = 3),
            locationEnabled = true,
        )

        val decoded = decodeSettingsExportFromYaml(encodeSettingsExportToYaml(original))

        assertEquals(original, decoded)
    }
}
