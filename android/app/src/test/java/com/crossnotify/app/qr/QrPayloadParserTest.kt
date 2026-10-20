package com.crossnotify.app.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPayloadParserTest {

    @Test
    fun `parses valid cn-relay QR payload`() {
        val json = """
            {
              "t": "cn-relay",
              "v": 1,
              "server":   { "url": "https://notify.example.com", "name": "Home Server" },
              "channel":  { "id": "3f1c-uuid", "name": "Work phone", "color": "#4F46E5" },
              "apiKey":   "cnr_testkey1234567890",
              "firebase": { "projectId": "crossnotify-xyz", "appId": "1:1234567890:android:abc123", "apiKey": "AIzaSyTest", "senderId": "1234567890" },
              "exp": 1792014958000
            }
        """.trimIndent()

        val result = QrPayloadParser.parse(json)
        assertTrue(result.isSuccess)
        val parsed = result.getOrThrow() as ParsedQrPayload.RelayProvisioning
        assertEquals("Home Server", parsed.payload.server.name)
        assertEquals("3f1c-uuid", parsed.payload.channel.id)
        assertEquals("cnr_testkey1234567890", parsed.payload.apiKey)
        assertEquals("crossnotify-xyz", parsed.payload.firebase.projectId)
        assertFalse(QrPayloadParser.isInsecureHttp(parsed.payload.server.url))
    }

    @Test
    fun `parses valid cn-sub QR payload`() {
        val json = """
            {
              "t": "cn-sub",
              "v": 1,
              "server":   { "url": "http://192.168.1.50:3000", "name": "LAN Server" },
              "channel":  { "id": "3f1c-uuid", "name": "Work phone", "color": "#4F46E5", "relayDevice": "Pixel 8 Pro" },
              "apiKey":   "cns_subkey1234567890",
              "firebase": { "projectId": "crossnotify-xyz", "appId": "1:1234567890:android:abc123", "apiKey": "AIzaSyTest", "senderId": "1234567890" },
              "e2e":      { "alg": "A256GCM", "kid": "k1", "key": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" }
            }
        """.trimIndent()

        val result = QrPayloadParser.parse(json)
        assertTrue(result.isSuccess)
        val parsed = result.getOrThrow() as ParsedQrPayload.SubscriberPairing
        assertEquals("Pixel 8 Pro", parsed.payload.channel.relayDevice)
        assertEquals("cns_subkey1234567890", parsed.payload.apiKey)
        assertEquals("A256GCM", parsed.payload.e2e.alg)
        assertTrue(QrPayloadParser.isInsecureHttp(parsed.payload.server.url))
    }

    @Test
    fun `rejects unsupported version and unknown type`() {
        val futureVersion = """
            {
              "t": "cn-relay",
              "v": 99,
              "server": { "url": "https://notify.example.com", "name": "Home" },
              "channel": { "id": "1", "name": "Phone" },
              "apiKey": "cnr_123",
              "firebase": { "projectId": "p", "appId": "a", "apiKey": "k", "senderId": "s" },
              "exp": 1792014958000
            }
        """.trimIndent()

        assertTrue(QrPayloadParser.parse(futureVersion).isFailure)

        val unknownType = """
            {
              "t": "cn-unknown",
              "v": 1
            }
        """.trimIndent()

        assertTrue(QrPayloadParser.parse(unknownType).isFailure)
    }
}
