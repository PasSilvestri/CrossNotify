package com.crossnotify.app.di

import android.content.Context
import androidx.room.Room
import com.crossnotify.app.data.db.CrossNotifyDatabase
import com.crossnotify.app.data.db.InboxDao
import com.crossnotify.app.data.db.OutboxDao
import com.crossnotify.app.data.db.RelayChannelDao
import com.crossnotify.app.data.db.SeenOriginDao
import com.crossnotify.app.data.db.ServerDao
import com.crossnotify.app.data.db.SubscriptionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CrossNotifyDatabase {
        return Room.databaseBuilder(
            context,
            CrossNotifyDatabase::class.java,
            "crossnotify.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideServerDao(db: CrossNotifyDatabase): ServerDao = db.serverDao()

    @Provides
    fun provideRelayChannelDao(db: CrossNotifyDatabase): RelayChannelDao = db.relayChannelDao()

    @Provides
    fun provideSubscriptionDao(db: CrossNotifyDatabase): SubscriptionDao = db.subscriptionDao()

    @Provides
    fun provideInboxDao(db: CrossNotifyDatabase): InboxDao = db.inboxDao()

    @Provides
    fun provideOutboxDao(db: CrossNotifyDatabase): OutboxDao = db.outboxDao()

    @Provides
    fun provideSeenOriginDao(db: CrossNotifyDatabase): SeenOriginDao = db.seenOriginDao()
}
