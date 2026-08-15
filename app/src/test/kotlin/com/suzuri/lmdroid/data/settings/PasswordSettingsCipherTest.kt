package com.suzuri.lmdroid.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * Pure-JVM tests for the password-protected export format — [PasswordSettingsCipher] deliberately
 * avoids android.* APIs (unlike [ApiKeyCipher]) precisely so this can run without Robolectric.
 * A tiny iteration count keeps each derivation fast; the security-relevant behavior under test
 * (salt/IV handling, GCM authentication, envelope plumbing) doesn't depend on its size.
 */
class PasswordSettingsCipherTest {

    private val cipher = PasswordSettingsCipher(kdfIterations = 1_000)

    @Test
    fun `round-trips a settings document through encrypt and decrypt`() {
        val plaintext = encodeSettingsExportToYaml(
            SettingsExport(
                exportedAt = "2026-08-15T00:00:00Z",
                apiProfiles = listOf(
                    ExportedApiProfile(
                        id = 1,
                        name = "ローカルサーバー",
                        baseUrl = "http://localhost:8080/v1",
                        enabled = true,
                        plainApiKey = "sk-live-秘密のキー",
                        models = listOf("gpt-4o-mini"),
                    ),
                ),
                markdownEnabled = true,
            ),
        )

        val envelopeYaml = cipher.encrypt(plaintext, "正しいパスワード")

        assertEquals(plaintext, cipher.decrypt(envelopeYaml, "正しいパスワード"))
    }

    @Test
    fun `rejects a wrong password with a GCM authentication failure`() {
        val envelopeYaml = cipher.encrypt("exportedAt: now", "正しいパスワード")

        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(envelopeYaml, "間違ったパスワード") }
    }

    @Test
    fun `rejects a tampered payload with a GCM authentication failure`() {
        val envelopeYaml = cipher.encrypt("exportedAt: now", "password")
        val envelope = settingsExportYaml.decodeFromString(EncryptedSettingsEnvelope.serializer(), envelopeYaml)
        // Flip one base64 character of the ciphertext — GCM must refuse the result outright.
        val tamperedChar = if (envelope.payload[0] == 'A') 'B' else 'A'
        val tampered = envelope.copy(payload = tamperedChar + envelope.payload.substring(1))

        assertThrows(AEADBadTagException::class.java) {
            cipher.decrypt(settingsExportYaml.encodeToString(EncryptedSettingsEnvelope.serializer(), tampered), "password")
        }
    }

    @Test
    fun `the envelope never contains the plaintext, and carries the format marker`() {
        val secret = "sk-live-super-secret-key"

        val envelopeYaml = cipher.encrypt("apiKey: $secret", "password")

        assertFalse(envelopeYaml.contains(secret))
        assertTrue(envelopeYaml.contains(EncryptedSettingsEnvelope.FORMAT_ID))
    }

    @Test
    fun `decrypt honors the iteration count recorded in the envelope, not its own default`() {
        val producer = PasswordSettingsCipher(kdfIterations = 1_000)
        val consumerWithDifferentDefault = PasswordSettingsCipher(kdfIterations = 2_000)
        val envelopeYaml = producer.encrypt("exportedAt: now", "password")

        assertEquals("exportedAt: now", consumerWithDifferentDefault.decrypt(envelopeYaml, "password"))
    }

    @Test
    fun `encrypt refuses an empty password`() {
        assertThrows(IllegalArgumentException::class.java) { cipher.encrypt("exportedAt: now", "") }
    }

    @Test
    fun `format probe recognizes an encrypted envelope but not a plain export or garbage`() {
        val envelopeYaml = cipher.encrypt("exportedAt: now", "password")
        val plainExportYaml = encodeSettingsExportToYaml(
            SettingsExport(exportedAt = "2026-08-15T00:00:00Z", apiProfiles = emptyList(), markdownEnabled = true),
        )

        assertTrue(isEncryptedSettingsExport(envelopeYaml))
        assertFalse(isEncryptedSettingsExport(plainExportYaml))
        assertFalse(isEncryptedSettingsExport("this is not yaml at all: ["))
    }
}
