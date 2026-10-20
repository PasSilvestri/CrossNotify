package com.crossnotify.app.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class OriginInfo(
    val id: String,
    val hops: Int = 0,
    val path: List<String> = emptyList()
)

@Serializable
data class NotificationPlaintext(
    val v: Int = 1,
    val id: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val subText: String = "",
    val bigText: String = "",
    val category: String = "",
    val postedAt: Long,
    val origin: OriginInfo
)

data class EncryptedNotificationBlob(
    val iv: String,
    val ciphertext: String
)

/**
 * End-to-end encryption engine for channel notifications:
 * - Cipher: AES-256-GCM with a unique 12-byte random IV per message and 128-bit authentication tag.
 * - Key generation: 32 cryptographically secure random bytes.
 * - Associated Authenticated Data (AAD): bound to `cn1|<channelId>|<notificationId>` to prevent tampering or replay across channels.
 * - Wire encoding: unpadded URL-safe base64 strings for both IV and ciphertext.
 * - Payload safety: enforces maximum character boundaries on title, summary, and expanded text fields.
 */
@Singleton
class E2ECryptoEngine @Inject constructor() {

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_LENGTH_BYTES = 32
        private const val IV_LENGTH_BYTES = 12
        private const val GCM_TAG_BITS = 128

        const val MAX_TITLE_CHARS = 200
        const val MAX_TEXT_CHARS = 1000
        const val MAX_BIG_TEXT_CHARS = 4000

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun truncateWithEllipsis(input: String, maxLength: Int): String {
            if (input.length <= maxLength) return input
            if (maxLength <= 1) return "…".take(maxLength)
            return input.substring(0, maxLength - 1) + "…"
        }

        fun sanitizePayload(payload: NotificationPlaintext): NotificationPlaintext {
            return payload.copy(
                title = truncateWithEllipsis(payload.title, MAX_TITLE_CHARS),
                text = truncateWithEllipsis(payload.text, MAX_TEXT_CHARS),
                bigText = truncateWithEllipsis(payload.bigText, MAX_BIG_TEXT_CHARS)
            )
        }

        fun buildAad(channelId: String, notificationId: String): ByteArray {
            require(channelId.isNotBlank()) { "channelId must not be blank" }
            require(notificationId.isNotBlank()) { "notificationId must not be blank" }
            return "cn1|$channelId|$notificationId".toByteArray(Charsets.UTF_8)
        }

        fun encodeBase64Url(bytes: ByteArray): String {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun decodeBase64Url(encoded: String): ByteArray {
            return Base64.getUrlDecoder().decode(encoded)
        }
    }

    private val secureRandom = SecureRandom()

    /**
     * Generates a fresh 256-bit (32-byte) AES key using [SecureRandom].
     */
    fun generateE2EKey(): ByteArray {
        val key = ByteArray(KEY_LENGTH_BYTES)
        secureRandom.nextBytes(key)
        return key
    }

    /**
     * Encrypts [payload] using AES-256-GCM bound to [channelId] and `payload.id` via AAD.
     * [ivOverride] is optional and intended only for deterministic unit test vectors.
     */
    fun encrypt(
        e2eKey: ByteArray,
        channelId: String,
        payload: NotificationPlaintext,
        ivOverride: ByteArray? = null
    ): EncryptedNotificationBlob {
        require(e2eKey.size == KEY_LENGTH_BYTES) {
            "E2E key must be exactly $KEY_LENGTH_BYTES bytes (got ${e2eKey.size})"
        }

        val sanitized = sanitizePayload(payload)
        val plaintextBytes = json.encodeToString(sanitized).toByteArray(Charsets.UTF_8)

        val iv = if (ivOverride != null) {
            require(ivOverride.size == IV_LENGTH_BYTES) {
                "IV override must be $IV_LENGTH_BYTES bytes"
            }
            ivOverride.copyOf()
        } else {
            ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        }

        val aad = buildAad(channelId, sanitized.id)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(e2eKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(aad)

        val ciphertextWithTag = cipher.doFinal(plaintextBytes)
        return EncryptedNotificationBlob(
            iv = encodeBase64Url(iv),
            ciphertext = encodeBase64Url(ciphertextWithTag)
        )
    }

    /**
     * Decrypts a base64url-encoded [ivBase64Url] and [ciphertextBase64Url] using [e2eKey]
     * and verifies the AAD `cn1|<channelId>|<notificationId>`.
     */
    fun decrypt(
        e2eKey: ByteArray,
        channelId: String,
        notificationId: String,
        ivBase64Url: String,
        ciphertextBase64Url: String
    ): NotificationPlaintext {
        require(e2eKey.size == KEY_LENGTH_BYTES) {
            "E2E key must be exactly $KEY_LENGTH_BYTES bytes (got ${e2eKey.size})"
        }

        val iv = decodeBase64Url(ivBase64Url)
        require(iv.size == IV_LENGTH_BYTES) {
            "Decoded IV must be exactly $IV_LENGTH_BYTES bytes (got ${iv.size})"
        }
        val ciphertextWithTag = decodeBase64Url(ciphertextBase64Url)
        require(ciphertextWithTag.size >= 16) {
            "Ciphertext too short to contain GCM authentication tag"
        }

        val aad = buildAad(channelId, notificationId)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(e2eKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(aad)

        val decryptedBytes = cipher.doFinal(ciphertextWithTag)
        val parsed = json.decodeFromString<NotificationPlaintext>(
            String(decryptedBytes, Charsets.UTF_8)
        )
        require(parsed.id == notificationId) {
            "Decrypted payload notificationId (${parsed.id}) does not match AAD notificationId ($notificationId)"
        }
        return parsed
    }
}
