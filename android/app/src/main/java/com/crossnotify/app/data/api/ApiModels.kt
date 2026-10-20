package com.crossnotify.app.data.api

import kotlinx.serialization.Serializable

@Serializable
data class RelayInitRequest(
    val deviceName: String,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String
)

@Serializable
data class RelayInitResponse(
    val channelId: String,
    val channelName: String,
    val color: String? = null,
    val serverName: String = "CrossNotify Server",
    val notificationTtlHours: Int = 48
)

@Serializable
data class ChannelInfoResponse(
    val channelId: String,
    val name: String,
    val color: String? = null,
    val status: String,
    val relayDeviceName: String? = null,
    val subscriberCount: Int = 0,
    val notificationTtlHours: Int = 48
)

@Serializable
data class PublishNotificationRequest(
    val notificationId: String,
    val iv: String,
    val ciphertext: String,
    val appName: String? = null,
    val timestamp: Long? = null
)

@Serializable
data class PublishNotificationResponse(
    val notificationId: String,
    val receivedAt: Long
)

@Serializable
data class MintSubscriberKeyResponse(
    val subscriberId: String,
    val apiKey: String,
    val expiresAt: Long
)

@Serializable
data class SubscriberInitRequest(
    val fcmToken: String,
    val deviceName: String,
    val deviceModel: String,
    val deliveryMode: String = "battery_saver"
)

@Serializable
data class SubscriberInitResponse(
    val subscriberId: String,
    val channelId: String,
    val channelName: String,
    val deliveryMode: String
)

@Serializable
data class SubscriberUpdateSelfRequest(
    val fcmToken: String? = null,
    val deviceName: String? = null,
    val deliveryMode: String? = null
)

@Serializable
data class SubscriberUpdateSelfResponse(
    val subscriberId: String,
    val deliveryMode: String
)

@Serializable
data class RemoteNotificationDto(
    val notificationId: String,
    val iv: String,
    val ciphertext: String,
    val appName: String? = null,
    val timestamp: Long? = null,
    val receivedAt: Long
)

@Serializable
data class CatchUpResponse(
    val notifications: List<RemoteNotificationDto> = emptyList(),
    val hasMore: Boolean = false
)

@Serializable
data class StatusOkResponse(
    val status: String = "ok"
)

@Serializable
data class ApiErrorDto(
    val error: String = "unknown_error",
    val detail: String? = null,
    val message: String? = null
)

class CrossNotifyApiException(
    val httpCode: Int,
    val errorCode: String,
    override val message: String
) : Exception("HTTP $httpCode ($errorCode): $message") {
    val isRevokedOrUnauthorized: Boolean
        get() = httpCode == 401 || httpCode == 403 || (httpCode == 409 && errorCode == "channel_revoked")
}
