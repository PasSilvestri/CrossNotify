package com.crossnotify.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.crossnotify.app.data.repository.CrossNotifyRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class CatchUpSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: CrossNotifyRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_CHANNEL_ID = "channel_id"
    }

    override suspend fun doWork(): Result {
        val channelId = inputData.getString(KEY_CHANNEL_ID)
        val ok = if (!channelId.isNullOrBlank()) {
            repository.syncCatchUpForSubscription(channelId)
        } else {
            repository.syncCatchUpAllSubscriptions()
        }
        return if (ok) Result.success() else Result.retry()
    }
}
