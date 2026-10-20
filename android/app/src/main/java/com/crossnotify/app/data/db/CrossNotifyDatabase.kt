package com.crossnotify.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ServerEntity::class,
        RelayChannelEntity::class,
        RelayAppFilterEntity::class,
        SubscriptionEntity::class,
        InboxEntity::class,
        OutboxEntity::class,
        SeenOriginEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class CrossNotifyDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun relayChannelDao(): RelayChannelDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun inboxDao(): InboxDao
    abstract fun outboxDao(): OutboxDao
    abstract fun seenOriginDao(): SeenOriginDao
}
