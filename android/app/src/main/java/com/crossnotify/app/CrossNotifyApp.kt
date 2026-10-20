package com.crossnotify.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.crossnotify.app.data.db.SubscriptionDao
import com.crossnotify.app.data.repository.CrossNotifyRepository
import com.crossnotify.app.firebase.FirebaseProjectManager
import com.crossnotify.app.util.NotificationHelper
import com.crossnotify.app.worker.TokenRefreshWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class CrossNotifyApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var firebaseProjectManager: FirebaseProjectManager

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var subscriptionDao: SubscriptionDao

    @Inject
    lateinit var repository: CrossNotifyRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Set up base system notification channels for relay notifications and alerts
        notificationHelper.ensureBaseChannelsCreated()

        // Reconnect known Firebase projects from the local database, register custom
        // channels for each subscription, and catch up on any missed notifications
        appScope.launch {
            firebaseProjectManager.initializeAllFromDatabase()
            subscriptionDao.getActive().forEach { sub ->
                notificationHelper.ensureSubscriptionChannel(sub)
            }
            repository.scheduleCatchUpSync()
            repository.refreshFcmTokensForAllSubscriptions()
        }

        // Schedule periodic maintenance to ensure registration tokens stay in sync with servers
        scheduleDailyTokenRefresh()
    }

    private fun scheduleDailyTokenRefresh() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<TokenRefreshWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_fcm_token_refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
