package com.suzuri.lmdroid.data.settings

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based encryption for settings exports — the counterpart of [ApiKeyCipher] for the
 * cross-device case. [ApiKeyCipher]'s key lives in the Android Keystore (non-exportable, device-
 * bound), so a plain YAML export's API keys are only decryptable by the exact app install that
 * wrote them. To carry settings — API keys included — to a different device or a reinstalled
 * app, the whole document is instead encrypted under a key derived from a user-supplied password:
 * PBKDF2-HMAC-SHA256 (with a fresh random salt) → AES-256-GCM key, then the entire inner YAML
 * (with the API keys in plaintext) is encrypted as one authenticated payload and wrapped in the
 * self-describing [EncryptedSettingsEnvelope] YAML below. Encrypting the whole document (rather
 * than just the key fields) means a wrong password is rejected by GCM authentication before
 * anything is parsed or applied, and profile names/URLs aren't leaked without the password.
 *
 * Deliberately plain JCE + [java.util.Base64] (no android.* APIs) so this stays unit-testable on
 * the JVM, unlike [ApiKeyCipher].
 */
class PasswordSettingsCipher(
    // Iterations used when encrypting; decryption always honors whatever the envelope records.
    // OWASP's 2023 recommendation for PBKDF2-HMAC-SHA256. One-shot export/import only, so the
    // extra derivation cost is acceptable (and callers run it off the main thread).
    private val kdfIterations: Int = DEFAULT_KDF_ITERATIONS,
) {

    /** Encrypts [plaintext] under [password] and returns the envelope YAML. */
    fun encrypt(plaintext: String, password: String): String {
        require(password.isNotEmpty()) { "password must not be empty" }
        val salt = randomBytes(SALT_LENGTH_BYTES)
        val iv = randomBytes(IV_LENGTH_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, kdfIterations), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val payload = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val base64 = Base64.getEncoder()
        return settingsExportYaml.encodeToString(
            EncryptedSettingsEnvelope.serializer(),
            EncryptedSettingsEnvelope(
                kdfIterations = kdfIterations,
                salt = base64.encodeToString(salt),
                iv = base64.encodeToString(iv),
                payload = base64.encodeToString(payload),
            ),
        )
    }

    /**
     * Decrypts an envelope produced by [encrypt]. Throws [AEADBadTagException] when the password
     * is wrong (GCM authentication failure) and [IllegalArgumentException] when [envelopeYaml]
     * isn't a valid/compatible envelope at all.
     */
    fun decrypt(envelopeYaml: String, password: String): String {
        val envelope = settingsExportYaml.decodeFromString(EncryptedSettingsEnvelope.serializer(), envelopeYaml)
        require(envelope.format == EncryptedSettingsEnvelope.FORMAT_ID) { "Unrecognized settings envelope format: ${envelope.format}" }
        require(envelope.version == 1) { "Unsupported settings envelope version: ${envelope.version}" }
        require(envelope.kdf == KDF_ALGORITHM) { "Unsupported key derivation algorithm: ${envelope.kdf}" }
        require(envelope.kdfIterations >= 1) { "Invalid kdfIterations: ${envelope.kdfIterations}" }
        val base64 = Base64.getDecoder()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(password, base64.decode(envelope.salt), envelope.kdfIterations),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, base64.decode(envelope.iv)),
        )
        return String(cipher.doFinal(base64.decode(envelope.payload)), Charsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, AES_KEY_LENGTH_BITS)
        try {
            val derived = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
            return SecretKeySpec(derived, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomBytes(length: Int): ByteArray =
        ByteArray(length).also { SecureRandom().nextBytes(it) }

    companion object {
        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val AES_KEY_LENGTH_BITS = 256
        private const val SALT_LENGTH_BYTES = 16
        private const val IV_LENGTH_BYTES = 12
        const val DEFAULT_KDF_ITERATIONS = 600_000
    }
}

/**
 * The self-describing wrapper written by [PasswordSettingsCipher.encrypt] — still YAML so the
 * .yaml file stays recognizable/inspectable, but the actual settings only exist inside [payload]
 * (base64 AES-256-GCM ciphertext, tag appended). The salt/IV/iteration fields carry everything
 * needed to re-derive the key, so decryption never depends on this device's state.
 */
@Serializable
data class EncryptedSettingsEnvelope(
    val format: String = FORMAT_ID,
    val version: Int = 1,
    val kdf: String = "PBKDF2WithHmacSHA256",
    val kdfIterations: Int,
    val salt: String,
    val iv: String,
    val payload: String,
) {
    companion object {
        const val FORMAT_ID = "lmdroid-encrypted-settings-v1"
    }
}

/**
 * Thrown by [SettingsImporter] when a password-protected file fails GCM authentication — i.e. the
 * entered password doesn't match the one used at export time. Kept distinct from generic parse
 * failures so the UI can tell the user "wrong password" instead of "invalid file."
 */
class WrongPasswordException : IllegalStateException("The supplied password could not decrypt this settings export")

/**
 * Whether [yamlText] is a [EncryptedSettingsEnvelope] rather than a plain settings export — a
 * lenient probe that only reads the `format` marker and ignores everything else, so any future
 * format that keeps the marker still routes correctly, and anything malformed simply reports
 * "not encrypted" (the legacy decode path then produces the user-visible error).
 */
fun isEncryptedSettingsExport(yamlText: String): Boolean = runCatching {
    settingsExportYaml.decodeFromString(FormatProbe.serializer(), yamlText).format == EncryptedSettingsEnvelope.FORMAT_ID
}.getOrDefault(false)

/** Never serialized — only exists to sniff the `format` marker via [isEncryptedSettingsExport]. */
@Serializable
private data class FormatProbe(
    val format: String? = null,
)
