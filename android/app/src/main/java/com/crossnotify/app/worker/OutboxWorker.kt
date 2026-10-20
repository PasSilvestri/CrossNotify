package com.crossnotify.app.worker

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.crossnotify.app.R
import com.crossnotify.app.data.db.RelayChannelDao
import com.crossnotify.app.data.repository.CrossNotifyRepository
import com.crossnotify.app.util.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class OutboxWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: CrossNotifyRepository,
    private val relayChannelDao: RelayChannelDao,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_CHANNEL_ID = "channel_id"
        private const val FOREGROUND_NOTIFICATION_ID = 9401
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        notificationHelper.ensureBaseChannelsCreated()
        val notification: Notification = NotificationCompat.Builder(
            applicationContext,
            NotificationHelper.CHANNEL_ALERTS
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("CrossNotify Relay")
            .setContentText("Dispatching encrypted notification…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        return ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result {
        val channelId = inputData.getString(KEY_CHANNEL_ID)
        return if (!channelId.isNullOrBlank()) {
            val ok = repository.processOutboxForChannel(channelId)
            if (ok) Result.success() else Result.retry()
        } else {
            val activeChannels = relayChannelDao.getActive()
            var allOk = true
            for (ch in activeChannels) {
                if (!repository.processOutboxForChannel(ch.channelId)) {
                    allOk = false
                }
            }
            if (allOk) Result.success() else Result.retry()
        }
    }
}
