package com.crossnotify.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "servers",
    indices = [Index(value = ["url"], unique = true)]
)
data class ServerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val name: String,
    val fbProjectId: String,
    val fbAppId: String,
    val fbApiKey: String,
    val fbSenderId: String,
    val fbAppName: String,
    val insecureHttpAccepted: Boolean = false
)

@Entity(
    tableName = "relay_channels",
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["serverId"])]
)
data class RelayChannelEntity(
    @PrimaryKey
    val channelId: String,
    val serverId: Long,
    val name: String,
    val color: String,
    val apiKeyEnc: String,
    val e2eKeyEnc: String,
    val kid: String = "k1",
    val filterMode: String = "deny_list", // "allow_list" or "deny_list"
    val sendAppName: Boolean = true,
    val active: Boolean = true,
    val createdAt: Long
)

@Entity(
    tableName = "relay_app_filters",
    primaryKeys = ["channelId", "packageName"],
    foreignKeys = [
        ForeignKey(
            entity = RelayChannelEntity::class,
            parentColumns = ["channelId"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["channelId"])]
)
data class RelayAppFilterEntity(
    val channelId: String,
    val packageName: String
)

@Entity(
    tableName = "subscriptions",
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["serverId"])]
)
data class SubscriptionEntity(
    @PrimaryKey
    val channelId: String,
    val serverId: Long,
    val subscriberId: String,
    val name: String,
    val color: String,
    val relayDevice: String,
    val apiKeyEnc: String,
    val e2eKeyEnc: String,
    val kid: String = "k1",
    val deliveryMode: String = "battery_saver", // "battery_saver" or "immediate_preview"
    val lastReceivedAt: Long = 0L,
    val forwardToRelayChannelId: String? = null,
    val lastRegisteredFcmToken: String? = null,
    val status: String = "active" // "active" or "revoked"
)

@Entity(
    tableName = "inbox",
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionEntity::class,
            parentColumns = ["channelId"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["channelId"]),
        Index(value = ["receivedAt"])
    ]
)
data class InboxEntity(
    @PrimaryKey
    val notificationId: String,
    val channelId: String,
    val originId: String,
    val iv: String,
    val ciphertext: String,
    val appName: String?,
    val relayTimestamp: Long,
    val receivedAt: Long,
    val readAt: Long? = null
)

@Entity(
    tableName = "outbox",
    foreignKeys = [
        ForeignKey(
            entity = RelayChannelEntity::class,
            parentColumns = ["channelId"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["channelId"]),
        Index(value = ["createdAt"])
    ]
)
data class OutboxEntity(
    @PrimaryKey
    val notificationId: String,
    val channelId: String,
    val iv: String,
    val ciphertext: String,
    val appName: String?,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null
)

@Entity(tableName = "seen_origins")
data class SeenOriginEntity(
    @PrimaryKey
    val originId: String,
    val seenAt: Long
)
