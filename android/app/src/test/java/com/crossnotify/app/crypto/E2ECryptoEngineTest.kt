package com.crossnotify.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.AEADBadTagException

class E2ECryptoEngineTest {

    private val engine = E2ECryptoEngine()

    @Test
    fun `encrypt and decrypt round-trip preserves full NotificationPlaintext`() {
        val e2eKey = engine.generateE2EKey()
        assertEquals(32, e2eKey.size)

        val channelId = "3f1c8b90-1234-4a5b-9c8d-0123456789ab"
        val notificationId = "a1b2c3d4-e5f6-4789-8012-3456789abcde"

        val payload = NotificationPlaintext(
            v = 1,
            id = notificationId,
            pkg = "com.whatsapp",
            app = "WhatsApp",
            title = "Alice",
            text = "Are we still meeting today?",
            subText = "2 new messages",
            bigText = "Are we still meeting today at 18:00?",
            category = "msg",
            postedAt = 1792014058000L,
            origin = OriginInfo(
                id = notificationId,
                hops = 0,
                path = listOf(channelId)
            )
        )

        val encrypted = engine.encrypt(e2eKey, channelId, payload)

        // Wire format checks: base64url without padding ('=' must not appear)
        assertFalse(encrypted.iv.contains("="))
        assertFalse(encrypted.ciphertext.contains("="))
        assertEquals(12, E2ECryptoEngine.decodeBase64Url(encrypted.iv).size)

        val decrypted = engine.decrypt(
            e2eKey = e2eKey,
            channelId = channelId,
            notificationId = notificationId,
            ivBase64Url = encrypted.iv,
            ciphertextBase64Url = encrypted.ciphertext
        )

        assertEquals(payload, decrypted)
    }

    @Test
    fun `deterministic AES-256-GCM vector matches wire format and AAD binding`() {
        val fixedKey = ByteArray(32) { i -> (i + 1).toByte() }
        val fixedIv = ByteArray(12) { i -> (0xA0 + i).toByte() }
        val channelId = "channel-vector-001"
        val notificationId = "notif-vector-001"

        val payload = NotificationPlaintext(
            v = 1,
            id = notificationId,
            pkg = "org.thoughtcrime.securesms",
            app = "Signal",
            title = "Bob",
            text = "Vector test message",
            subText = "",
            bigText = "Vector test message",
            category = "msg",
            postedAt = 1700000000000L,
            origin = OriginInfo(
                id = notificationId,
                hops = 0,
                path = listOf(channelId)
            )
        )

        val blob1 = engine.encrypt(fixedKey, channelId, payload, ivOverride = fixedIv)
        val blob2 = engine.encrypt(fixedKey, channelId, payload, ivOverride = fixedIv)

        assertEquals("oKGio6Slpqeoqaqr", blob1.iv)
        assertEquals(blob1.iv, blob2.iv)
        assertEquals(blob1.ciphertext, blob2.ciphertext)

        // Random IV encryptions must produce distinct IVs and ciphertexts
        val randomBlob1 = engine.encrypt(fixedKey, channelId, payload)
        val randomBlob2 = engine.encrypt(fixedKey, channelId, payload)
        assertNotEquals(randomBlob1.iv, randomBlob2.iv)
        assertNotEquals(randomBlob1.ciphertext, randomBlob2.ciphertext)

        val decrypted = engine.decrypt(
            e2eKey = fixedKey,
            channelId = channelId,
            notificationId = notificationId,
            ivBase64Url = blob1.iv,
            ciphertextBase64Url = blob1.ciphertext
        )
        assertEquals(payload, decrypted)
        assertArrayEquals(
            "cn1|channel-vector-001|notif-vector-001".toByteArray(Charsets.UTF_8),
            E2ECryptoEngine.buildAad(channelId, notificationId)
        )
    }

    @Test
    fun `swapping channelId or notificationId in AAD fails authentication`() {
        val e2eKey = engine.generateE2EKey()
        val channelId = "channel-alpha"
        val notificationId = "notif-100"

        val payload = NotificationPlaintext(
            id = notificationId,
            pkg = "com.example.chat",
            app = "Chat",
            title = "Hello",
            text = "Secret",
            postedAt = 1792014058000L,
            origin = OriginInfo(id = notificationId, hops = 0, path = listOf(channelId))
        )

        val encrypted = engine.encrypt(e2eKey, channelId, payload)

        // Wrong channelId
        try {
            engine.decrypt(
                e2eKey = e2eKey,
                channelId = "channel-beta",
                notificationId = notificationId,
                ivBase64Url = encrypted.iv,
                ciphertextBase64Url = encrypted.ciphertext
            )
            fail("Expected AEADBadTagException when channelId does not match AAD")
        } catch (_: AEADBadTagException) {
            // Expected
        }

        // Wrong notificationId
        try {
            engine.decrypt(
                e2eKey = e2eKey,
                channelId = channelId,
                notificationId = "notif-999",
                ivBase64Url = encrypted.iv,
                ciphertextBase64Url = encrypted.ciphertext
            )
            fail("Expected AEADBadTagException when notificationId does not match AAD")
        } catch (_: AEADBadTagException) {
            // Expected
        }
    }

    @Test
    fun `tampered ciphertext is rejected by GCM tag check`() {
        val e2eKey = engine.generateE2EKey()
        val channelId = "channel-tamper"
        val notificationId = "notif-tamper"

        val payload = NotificationPlaintext(
            id = notificationId,
            pkg = "com.example",
            app = "Example",
            title = "Sensitive",
            text = "Do not modify",
            postedAt = 1792014058000L,
            origin = OriginInfo(id = notificationId, hops = 0, path = listOf(channelId))
        )

        val encrypted = engine.encrypt(e2eKey, channelId, payload)
        val rawCt = E2ECryptoEngine.decodeBase64Url(encrypted.ciphertext)
        rawCt[0] = (rawCt[0].toInt() xor 0x01).toByte()
        val tamperedCt = E2ECryptoEngine.encodeBase64Url(rawCt)

        try {
            engine.decrypt(
                e2eKey = e2eKey,
                channelId = channelId,
                notificationId = notificationId,
                ivBase64Url = encrypted.iv,
                ciphertextBase64Url = tamperedCt
            )
            fail("Expected AEADBadTagException on tampered ciphertext")
        } catch (_: AEADBadTagException) {
            // Expected
        }
    }

    @Test
    fun `payload field limits truncate title, text, and bigText with ellipsis`() {
        val e2eKey = engine.generateE2EKey()
        val channelId = "channel-limits"
        val notificationId = "notif-limits"

        val longTitle = "T".repeat(300)
        val longText = "X".repeat(1500)
        val longBigText = "B".repeat(5000)

        val payload = NotificationPlaintext(
            id = notificationId,
            pkg = "com.example",
            app = "Example",
            title = longTitle,
            text = longText,
            bigText = longBigText,
            postedAt = 1792014058000L,
            origin = OriginInfo(id = notificationId, hops = 0, path = listOf(channelId))
        )

        val encrypted = engine.encrypt(e2eKey, channelId, payload)
        val decrypted = engine.decrypt(
            e2eKey = e2eKey,
            channelId = channelId,
            notificationId = notificationId,
            ivBase64Url = encrypted.iv,
            ciphertextBase64Url = encrypted.ciphertext
        )

        assertEquals(200, decrypted.title.length)
        assertTrue(decrypted.title.endsWith("…"))

        assertEquals(1000, decrypted.text.length)
        assertTrue(decrypted.text.endsWith("…"))

        assertEquals(4000, decrypted.bigText.length)
        assertTrue(decrypted.bigText.endsWith("…"))
    }

    @Test
    fun `encrypt and decrypt round-trip preserves optional icon base64 payload`() {
        val e2eKey = engine.generateE2EKey()
        val channelId = "channel-icon-001"
        val notificationId = "notif-icon-001"
        val fakeIconBase64 = "iVBORw0KGgoAAAANSUhEUgAAADAAAAAwCAYAAABXAvmHAAAFDElEQVR42u2Yf..."

        val payload = NotificationPlaintext(
            v = 1,
            id = notificationId,
            pkg = "com.telegram.messenger",
            app = "Telegram",
            icon = fakeIconBase64,
            title = "Channel Alert",
            text = "Icon roundtrip test",
            postedAt = 1792014058000L,
            origin = OriginInfo(id = notificationId, hops = 0, path = listOf(channelId))
        )

        val encrypted = engine.encrypt(e2eKey, channelId, payload)
        val decrypted = engine.decrypt(
            e2eKey = e2eKey,
            channelId = channelId,
            notificationId = notificationId,
            ivBase64Url = encrypted.iv,
            ciphertextBase64Url = encrypted.ciphertext
        )

        assertEquals(fakeIconBase64, decrypted.icon)
        assertEquals("Telegram", decrypted.app)
        assertEquals(payload, decrypted)
    }
}
