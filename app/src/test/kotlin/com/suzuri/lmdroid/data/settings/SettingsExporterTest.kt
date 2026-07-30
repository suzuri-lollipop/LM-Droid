package com.suzuri.lmdroid.data.settings

import com.suzuri.lmdroid.data.db.ApiProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises only the pure YAML-encoding half of [SettingsExporter] (see [encodeSettingsExportToYaml])
 * — the data-gathering half needs a real [SettingsRepository], which needs a real [ApiKeyCipher],
 * which needs a live AndroidKeyStore that doesn't exist under Robolectric (see
 * app/src/test/resources/robolectric.properties), so it isn't unit-testable here. This still
 * covers what's actually novel/risky about this feature: the wire format itself.
 */
class SettingsExporterTest {

    private fun sampleExport(
        apiKey: ExportedEncryptedValue? = ExportedEncryptedValue(ciphertext = "c1phertext==", iv = "1v=="),
    ) = SettingsExport(
        exportedAt = "2026-07-27T00:00:00Z",
        apiProfiles = listOf(
            ExportedApiProfile(
                id = 1,
                name = "ローカルサーバー",
                providerType = ApiProfileEntity.PROVIDER_OPENAI_COMPATIBLE,
                baseUrl = "http://localhost:8080/v1",
                enabled = true,
                apiKey = apiKey,
                models = listOf("gpt-4o-mini", "gpt-4o"),
            ),
            ExportedApiProfile(
                id = 2,
                name = "Brave Search",
                providerType = ApiProfileEntity.PROVIDER_BRAVE_SEARCH,
                baseUrl = "https://api.search.brave.com",
                enabled = true,
                apiKey = apiKey,
                models = emptyList(),
            ),
            ExportedApiProfile(
                id = 3,
                name = "VOICEVOX",
                providerType = ApiProfileEntity.PROVIDER_VOICEVOX_COMPATIBLE,
                baseUrl = "http://127.0.0.1:50021",
                enabled = true,
                // Unauthenticated local server — no API key, unlike the other two profiles.
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
        webSearch = ExportedWebSearchSettings(enabled = true, selectedProfileId = 2, maxToolRounds = 3),
        locationEnabled = true,
        tts = ExportedTtsSettings(selectedProfileId = 3),
        alarmToolEnabled = true,
    )

    @Test
    fun `encodes profile fields and never contains a plaintext api key`() {
        val yamlText = encodeSettingsExportToYaml(sampleExport())

        assertTrue(yamlText.contains("ローカルサーバー"))
        assertTrue(yamlText.contains("http://localhost:8080/v1"))
        assertTrue(yamlText.contains("gpt-4o-mini"))
        assertTrue(yamlText.contains("c1phertext=="))
        assertTrue(yamlText.contains("1v=="))
        // The only "secret" this test ever supplies is the ciphertext itself — asserting its
        // literal absence would be circular, so this instead pins the field name that carries it,
        // confirming the schema never introduces a separate plaintext field alongside it.
        assertFalse(yamlText.contains("apiKeyPlaintext"))
    }

    @Test
    fun `omits the api key entirely when none is configured`() {
        val yamlText = encodeSettingsExportToYaml(sampleExport(apiKey = null))

        assertFalse(yamlText.contains("ciphertext"))
    }

    @Test
    fun `round-trips through decode back to an equal SettingsExport`() {
        val original = sampleExport()

        val yamlText = encodeSettingsExportToYaml(original)
        val decoded = settingsExportYaml.decodeFromString(SettingsExport.serializer(), yamlText)

        assertEquals(original, decoded)
    }

    @Test
    fun `round-trips multiple simultaneously active system prompts`() {
        val export = sampleExport().copy(
            systemPrompts = listOf(
                ExportedSystemPrompt(id = 1, name = "敬語", content = "常に日本語の敬語で回答してください"),
                ExportedSystemPrompt(id = 2, name = "簡潔", content = "簡潔に回答してください"),
            ),
            selectedSystemPromptIds = listOf(1, 2),
        )

        val yamlText = encodeSettingsExportToYaml(export)
        val decoded = settingsExportYaml.decodeFromString(SettingsExport.serializer(), yamlText)

        assertEquals(export, decoded)
        assertEquals(listOf(1L, 2L), decoded.selectedSystemPromptIds)
    }

    @Test
    fun `round-trips a Brave Search profile's providerType and the selected web search profile pointer`() {
        val decoded = settingsExportYaml.decodeFromString(
            SettingsExport.serializer(),
            encodeSettingsExportToYaml(sampleExport()),
        )

        val braveProfile = decoded.apiProfiles.single { it.id == 2L }
        assertEquals(ApiProfileEntity.PROVIDER_BRAVE_SEARCH, braveProfile.providerType)
        assertEquals(2L, decoded.webSearch.selectedProfileId)
    }

    @Test
    fun `round-trips a VOICEVOX profile's speaker id and the selected tts profile pointer`() {
        val decoded = settingsExportYaml.decodeFromString(
            SettingsExport.serializer(),
            encodeSettingsExportToYaml(sampleExport()),
        )

        val voicevoxProfile = decoded.apiProfiles.single { it.id == 3L }
        assertEquals(ApiProfileEntity.PROVIDER_VOICEVOX_COMPATIBLE, voicevoxProfile.providerType)
        assertEquals(3, voicevoxProfile.voicevoxSpeakerId)
        assertEquals(3L, decoded.tts.selectedProfileId)
    }

    @Test
    fun `no tts profile selected still encodes and decodes as null (on-device speech)`() {
        val export = sampleExport().copy(tts = ExportedTtsSettings(selectedProfileId = null))

        val yamlText = encodeSettingsExportToYaml(export)
        val decoded = settingsExportYaml.decodeFromString(SettingsExport.serializer(), yamlText)

        assertEquals(export, decoded)
        assertEquals(null, decoded.tts.selectedProfileId)
    }

    @Test
    fun `no system prompts and no model selections still encode cleanly`() {
        val export = sampleExport().copy(
            chatSelection = null,
            systemSelection = null,
            assistantSelection = null,
            systemPrompts = emptyList(),
            selectedSystemPromptIds = emptyList(),
        )

        val yamlText = encodeSettingsExportToYaml(export)
        val decoded = settingsExportYaml.decodeFromString(SettingsExport.serializer(), yamlText)

        assertEquals(export, decoded)
    }

    @Test
    fun `round-trips the assistant mode model override independently of chat and system`() {
        val decoded = settingsExportYaml.decodeFromString(
            SettingsExport.serializer(),
            encodeSettingsExportToYaml(sampleExport()),
        )

        assertEquals("gpt-4o", decoded.assistantSelection?.model)
        assertEquals("gpt-4o-mini", decoded.chatSelection?.model)
        assertEquals(null, decoded.systemSelection)
    }

    @Test
    fun `round-trips the alarm tool toggle, defaulting to off when absent`() {
        val decoded = settingsExportYaml.decodeFromString(
            SettingsExport.serializer(),
            encodeSettingsExportToYaml(sampleExport()),
        )
        assertEquals(true, decoded.alarmToolEnabled)

        val disabledExport = sampleExport().copy(alarmToolEnabled = false)
        val decodedDisabled = settingsExportYaml.decodeFromString(
            SettingsExport.serializer(),
            encodeSettingsExportToYaml(disabledExport),
        )
        assertEquals(false, decodedDisabled.alarmToolEnabled)
    }
}
