package com.crossnotify.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN allowActions INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE inbox ADD COLUMN handledAt INTEGER")
            db.execSQL("ALTER TABLE inbox ADD COLUMN handledReason TEXT")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CrossNotifyDatabase {
        return Room.databaseBuilder(
            context,
            CrossNotifyDatabase::class.java,
            "crossnotify.db"
        )
            .addMigrations(MIGRATION_1_2)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.query("PRAGMA secure_delete = ON").close()
                }
            })
            .fallbackToDestructiveMigration()
            .build()
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
