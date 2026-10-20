package com.crossnotify.app.qr

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class QrServerInfo(
    val url: String,
    val name: String = "CrossNotify Server"
)

@Serializable
data class QrRelayChannelInfo(
    val id: String,
    val name: String,
    val color: String? = "#4F46E5"
)

@Serializable
data class QrSubChannelInfo(
    val id: String,
    val name: String,
    val color: String? = "#4F46E5",
    val relayDevice: String? = null
)

@Serializable
data class QrFirebaseInfo(
    val projectId: String,
    val appId: String,
    val apiKey: String,
    val senderId: String
)

@Serializable
data class QrE2EInfo(
    val alg: String = "A256GCM",
    val kid: String = "k1",
    val key: String
)

/**
 * `cn-relay`: provisioning payload encoded in the QR code displayed by the server dashboard.
 */
@Serializable
data class CnRelayQrPayload(
    val t: String = "cn-relay",
    val v: Int = 1,
    val server: QrServerInfo,
    val channel: QrRelayChannelInfo,
    val apiKey: String,
    val firebase: QrFirebaseInfo,
    val exp: Long
)

/**
 * `cn-sub`: subscriber pairing payload encoded in the QR code displayed by the relay phone.
 */
@Serializable
data class CnSubQrPayload(
    val t: String = "cn-sub",
    val v: Int = 1,
    val server: QrServerInfo,
    val channel: QrSubChannelInfo,
    val apiKey: String,
    val firebase: QrFirebaseInfo,
    val e2e: QrE2EInfo
)

sealed class ParsedQrPayload {
    data class RelayProvisioning(val payload: CnRelayQrPayload) : ParsedQrPayload()
    data class SubscriberPairing(val payload: CnSubQrPayload) : ParsedQrPayload()
}

object QrPayloadParser {
    const val SUPPORTED_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun parse(rawJson: String): Result<ParsedQrPayload> = runCatching {
        val trimmed = rawJson.trim()
        require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
            "Not a valid CrossNotify JSON QR code"
        }

        val root = json.parseToJsonElement(trimmed).jsonObject
        val type = root["t"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing QR payload type 't'")
        val version = root["v"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: throw IllegalArgumentException("Missing or invalid QR payload version 'v'")

        if (version > SUPPORTED_VERSION || version < 1) {
            throw IllegalArgumentException("Unsupported QR code version: v=$version (supported: $SUPPORTED_VERSION)")
        }

        when (type) {
            "cn-relay" -> {
                val payload = json.decodeFromString<CnRelayQrPayload>(trimmed)
                validateServerUrl(payload.server.url)
                require(payload.apiKey.startsWith("cnr_")) {
                    "Relay QR must contain a 'cnr_...' API key"
                }
                validateFirebase(payload.firebase)
                ParsedQrPayload.RelayProvisioning(payload)
            }
            "cn-sub" -> {
                val payload = json.decodeFromString<CnSubQrPayload>(trimmed)
                validateServerUrl(payload.server.url)
                require(payload.apiKey.startsWith("cns_")) {
                    "Subscriber QR must contain a 'cns_...' API key"
                }
                require(payload.e2e.alg == "A256GCM") {
                    "Unsupported E2E encryption algorithm: ${payload.e2e.alg}"
                }
                validateFirebase(payload.firebase)
                ParsedQrPayload.SubscriberPairing(payload)
            }
            else -> throw IllegalArgumentException("Unknown CrossNotify QR type: '$type'")
        }
    }

    fun encodeSubscriberQr(payload: CnSubQrPayload): String {
        return json.encodeToString(payload)
    }

    fun normalizeServerUrl(url: String): String {
        return url.trim().trimEnd('/')
    }

    fun isInsecureHttp(url: String): Boolean {
        return normalizeServerUrl(url).lowercase().startsWith("http://")
    }

    private fun validateServerUrl(url: String) {
        val normalized = normalizeServerUrl(url).lowercase()
        require(normalized.startsWith("https://") || normalized.startsWith("http://")) {
            "Server URL must start with https:// or http://"
        }
    }

    private fun validateFirebase(fb: QrFirebaseInfo) {
        require(fb.projectId.isNotBlank()) { "Firebase projectId is missing" }
        require(fb.appId.isNotBlank()) { "Firebase appId is missing" }
        require(fb.apiKey.isNotBlank()) { "Firebase apiKey is missing" }
        require(fb.senderId.isNotBlank()) { "Firebase senderId is missing" }
    }
}
