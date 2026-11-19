package com.crossnotify.app.data.api

import kotlinx.serialization.Serializable

@Serializable
data class RelayInitRequest(
    val deviceName: String,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val fcmToken: String? = null
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
data class RelayUpdateRequest(
    val deviceName: String? = null,
    val deviceModel: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
    val fcmToken: String? = null
)

@Serializable
data class RelayUpdateResponse(
    val status: String = "ok",
    val channelId: String? = null,
    val relayDeviceName: String? = null
)

@Serializable
data class SubscriberDeviceDto(
    val subscriberId: String,
    val deviceName: String? = null,
    val deviceModel: String? = null,
    val deliveryMode: String = "battery_saver",
    val allowActions: Boolean = true,
    val status: String = "active",
    val createdAt: Long = 0L,
    val activatedAt: Long? = null,
    val lastSeenAt: Long? = null
)

@Serializable
data class ChannelSubscribersResponse(
    val channelId: String,
    val subscribers: List<SubscriberDeviceDto> = emptyList()
)

data class ConnectionTestResult(
    val isSuccess: Boolean,
    val latencyMs: Long,
    val message: String
)

@Serializable
data class ChannelInfoResponse(
    val channelId: String,
    val name: String,
    val color: String? = null,
    val status: String,
    val relayDeviceName: String? = null,
    val subscriberCount: Int = 0,
    val notificationTtlHours: Int = 48,
    val subscribers: List<SubscriberDeviceDto>? = null,
    val allowActions: Boolean? = null
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
data class MintSubscriberKeyRequest(
    val allowActions: Boolean = true
)

@Serializable
data class MintSubscriberKeyResponse(
    val subscriberId: String,
    val apiKey: String,
    val expiresAt: Long,
    val allowActions: Boolean = true
)

@Serializable
data class UpdateSubscriberPermissionsRequest(
    val allowActions: Boolean
)

@Serializable
data class UpdateSubscriberPermissionsResponse(
    val status: String = "ok",
    val subscriberId: String,
    val allowActions: Boolean
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
    val deliveryMode: String,
    val allowActions: Boolean = true
)

@Serializable
data class SubscriberUpdateSelfRequest(
    val fcmToken: String? = null,
    val deviceName: String? = null,
    val deviceModel: String? = null,
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
    val receivedAt: Long,
    val handledAt: Long? = null,
    val handledReason: String? = null
)

@Serializable
data class DismissNotificationItem(
    val notificationId: String,
    val reason: String = "dismissed"
)

@Serializable
data class DismissNotificationsRequest(
    val items: List<DismissNotificationItem>
)

@Serializable
data class DismissNotificationsResponse(
    val status: String = "ok",
    val updatedCount: Int = 0
)

@Serializable
data class ExecuteActionRequest(
    val actionId: String,
    val iv: String,
    val ciphertext: String
)

@Serializable
data class ExecuteActionResponse(
    val status: String = "ok",
    val dispatched: Boolean = true,
    val handledAt: Long = 0L,
    val handledReason: String = "actioned"
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
data class SubscriberSilenceRequest(
    val appName: String? = null,
    val durationMs: Long? = null,
    val silencedUntil: Long? = null
)

@Serializable
data class SubscriberSilenceResponse(
    val status: String = "ok",
    val appName: String? = null,
    val silencedUntil: Long = 0L
)

@Serializable
data class SilencedAppDto(
    val appName: String,
    val silencedUntil: Long
)

@Serializable
data class SubscriberSilencesResponse(
    val channelSilencedUntil: Long? = null,
    val silencedApps: List<SilencedAppDto> = emptyList()
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
        get() = httpCode == 401 ||
            (httpCode == 403 && errorCode != "actions_not_allowed") ||
            (httpCode == 409 && errorCode == "channel_revoked")
}
