package com.suzuri.lmdroid.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts/decrypts the OpenAI API key using an AES-256-GCM key that lives
 * only in the Android Keystore (non-exportable, device-bound). We roll this
 * ourselves because androidx.security:security-crypto (EncryptedSharedPreferences)
 * has no stable release.
 */
class ApiKeyCipher {

    private val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    data class Encrypted(val ciphertextBase64: String, val ivBase64: String)

    fun encrypt(plaintext: String): Encrypted {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Encrypted(
            ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        )
    }

    fun decrypt(ciphertextBase64: String, ivBase64: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lmdroid_api_key_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
