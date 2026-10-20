package com.crossnotify.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.crossnotify.app.data.repository.CrossNotifyRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class TokenRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: CrossNotifyRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        repository.refreshFcmTokensForAllSubscriptions()
        return Result.success()
    }
}
