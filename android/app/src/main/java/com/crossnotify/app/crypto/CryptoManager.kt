package com.crossnotify.app.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the non-exportable Android Keystore AES-256-GCM master key (alias `cn_master`)
 * used to wrap sensitive columns in Room (`apiKeyEnc`, `e2eKeyEnc`).
 *
 * Uses StrongBox hardware backing when available on the device, falling back to TEE/Keystore.
 * Does NOT use deprecated EncryptedSharedPreferences.
 */
@Singleton
class CryptoManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "cn_master"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }

    @Synchronized
    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }

        val hasStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

        if (hasStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateKey(useStrongBox = true)
            } catch (_: Exception) {
                // Fallback to standard hardware-backed keystore if StrongBox generation fails
            }
        }
        return generateKey(useStrongBox = false)
    }

    private fun generateKey(useStrongBox: Boolean): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts [plaintext] bytes using the Android Keystore `cn_master` key.
     * Returns `<base64url(iv)>.<base64url(ciphertextWithTag)>`.
     */
    fun wrapBytes(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "${encoder.encodeToString(iv)}.${encoder.encodeToString(ciphertext)}"
    }

    /**
     * Decrypts a string produced by [wrapBytes] using the Android Keystore `cn_master` key.
     */
    fun unwrapBytes(wrapped: String): ByteArray {
        require(wrapped.isNotBlank() && wrapped.contains('.')) {
            "Malformed wrapped key blob"
        }
        val parts = wrapped.split('.', limit = 2)
        val decoder = Base64.getUrlDecoder()
        val iv = decoder.decode(parts[0])
        val ciphertext = decoder.decode(parts[1])

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), spec)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Convenience wrapper for UTF-8 strings (such as `cnr_...` and `cns_...` API keys).
     */
    fun wrapString(plaintext: String): String {
        return wrapBytes(plaintext.toByteArray(Charsets.UTF_8))
    }

    /**
     * Convenience unwrapper for UTF-8 strings.
     */
    fun unwrapString(wrapped: String): String {
        return String(unwrapBytes(wrapped), Charsets.UTF_8)
    }
}
