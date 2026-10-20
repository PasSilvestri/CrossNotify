package com.crossnotify.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY id ASC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY id ASC")
    suspend fun getAll(): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ServerEntity?

    @Query("SELECT * FROM servers WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: ServerEntity): Long

    @Update
    suspend fun update(server: ServerEntity)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """
        DELETE FROM servers
        WHERE id NOT IN (SELECT DISTINCT serverId FROM relay_channels)
          AND id NOT IN (SELECT DISTINCT serverId FROM subscriptions)
        """
    )
    suspend fun deleteOrphanedServers()
}

@Dao
interface RelayChannelDao {
    @Query("SELECT * FROM relay_channels ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RelayChannelEntity>>

    @Query("SELECT * FROM relay_channels ORDER BY createdAt DESC")
    suspend fun getAll(): List<RelayChannelEntity>

    @Query("SELECT * FROM relay_channels WHERE active = 1")
    suspend fun getActive(): List<RelayChannelEntity>

    @Query("SELECT * FROM relay_channels WHERE channelId = :channelId LIMIT 1")
    suspend fun getById(channelId: String): RelayChannelEntity?

    @Query("SELECT * FROM relay_channels WHERE channelId = :channelId LIMIT 1")
    fun observeById(channelId: String): Flow<RelayChannelEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(channel: RelayChannelEntity)

    @Update
    suspend fun update(channel: RelayChannelEntity)

    @Query("UPDATE relay_channels SET active = :active WHERE channelId = :channelId")
    suspend fun setActive(channelId: String, active: Boolean)

    @Query("UPDATE relay_channels SET filterMode = :filterMode WHERE channelId = :channelId")
    suspend fun setFilterMode(channelId: String, filterMode: String)

    @Query("UPDATE relay_channels SET sendAppName = :sendAppName WHERE channelId = :channelId")
    suspend fun setSendAppName(channelId: String, sendAppName: Boolean)

    @Query("DELETE FROM relay_channels WHERE channelId = :channelId")
    suspend fun deleteById(channelId: String)

    @Query("SELECT packageName FROM relay_app_filters WHERE channelId = :channelId")
    fun observeFilterPackages(channelId: String): Flow<List<String>>

    @Query("SELECT packageName FROM relay_app_filters WHERE channelId = :channelId")
    suspend fun getFilterPackages(channelId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFilter(filter: RelayAppFilterEntity)

    @Query("DELETE FROM relay_app_filters WHERE channelId = :channelId AND packageName = :packageName")
    suspend fun deleteFilter(channelId: String, packageName: String)
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY name ASC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY name ASC")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE status = 'active'")
    suspend fun getActive(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE channelId = :channelId LIMIT 1")
    suspend fun getById(channelId: String): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE channelId = :channelId LIMIT 1")
    fun observeById(channelId: String): Flow<SubscriptionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(subscription: SubscriptionEntity)

    @Update
    suspend fun update(subscription: SubscriptionEntity)

    @Query("UPDATE subscriptions SET lastReceivedAt = :lastReceivedAt WHERE channelId = :channelId AND :lastReceivedAt > lastReceivedAt")
    suspend fun updateLastReceivedAt(channelId: String, lastReceivedAt: Long)

    @Query("UPDATE subscriptions SET lastRegisteredFcmToken = :token WHERE channelId = :channelId")
    suspend fun updateLastRegisteredFcmToken(channelId: String, token: String)

    @Query("UPDATE subscriptions SET deliveryMode = :deliveryMode, forwardToRelayChannelId = CASE WHEN :deliveryMode = 'battery_saver' THEN NULL ELSE forwardToRelayChannelId END WHERE channelId = :channelId")
    suspend fun updateDeliveryMode(channelId: String, deliveryMode: String)

    @Query("UPDATE subscriptions SET forwardToRelayChannelId = :forwardToRelayChannelId WHERE channelId = :channelId")
    suspend fun updateForwardToRelayChannel(channelId: String, forwardToRelayChannelId: String?)

    @Query("UPDATE subscriptions SET status = 'revoked', apiKeyEnc = '', e2eKeyEnc = '', forwardToRelayChannelId = NULL WHERE channelId = :channelId")
    suspend fun markRevokedAndWipeKeys(channelId: String)

    @Query("DELETE FROM subscriptions WHERE channelId = :channelId")
    suspend fun deleteById(channelId: String)
}

@Dao
interface InboxDao {
    @Query("SELECT * FROM inbox ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<InboxEntity>>

    @Query("SELECT * FROM inbox WHERE channelId = :channelId ORDER BY receivedAt DESC")
    fun observeByChannel(channelId: String): Flow<List<InboxEntity>>

    @Query("SELECT * FROM inbox WHERE notificationId = :notificationId LIMIT 1")
    suspend fun getById(notificationId: String): InboxEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(item: InboxEntity): Long

    @Query("UPDATE inbox SET readAt = :readAt WHERE notificationId = :notificationId AND readAt IS NULL")
    suspend fun markRead(notificationId: String, readAt: Long)

    @Query("UPDATE inbox SET readAt = :readAt WHERE channelId = :channelId AND readAt IS NULL")
    suspend fun markChannelRead(channelId: String, readAt: Long)

    @Query("DELETE FROM inbox WHERE channelId = :channelId")
    suspend fun deleteByChannel(channelId: String)

    @Query("DELETE FROM inbox WHERE notificationId = :notificationId")
    suspend fun deleteById(notificationId: String)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox WHERE channelId = :channelId ORDER BY createdAt ASC")
    suspend fun getByChannel(channelId: String): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    suspend fun getAll(): List<OutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: OutboxEntity)

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error WHERE notificationId = :notificationId")
    suspend fun recordFailure(notificationId: String, error: String)

    @Query("DELETE FROM outbox WHERE notificationId = :notificationId")
    suspend fun deleteById(notificationId: String)

    @Query("DELETE FROM outbox WHERE channelId = :channelId")
    suspend fun deleteByChannel(channelId: String)
}

@Dao
interface SeenOriginDao {
    @Query("SELECT EXISTS(SELECT 1 FROM seen_origins WHERE originId = :originId)")
    suspend fun hasSeen(originId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markSeen(origin: SeenOriginEntity)

    @Query("DELETE FROM seen_origins WHERE seenAt < :cutoffMs")
    suspend fun pruneOlderThan(cutoffMs: Long)
}
