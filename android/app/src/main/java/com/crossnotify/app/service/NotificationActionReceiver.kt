package com.crossnotify.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.RemoteInput
import com.crossnotify.app.data.repository.CrossNotifyRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives notification action button taps and inline [RemoteInput] quick replies
 * triggered directly from CrossNotify's status-bar notifications in `immediate_preview` mode.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var repository: CrossNotifyRepository

    companion object {
        private const val TAG = "NotifActionReceiver"
        const val ACTION_EXECUTE_NOTIFICATION_ACTION = "com.crossnotify.app.EXECUTE_NOTIFICATION_ACTION"
        const val EXTRA_CHANNEL_ID = "channelId"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val EXTRA_ACTION_INDEX = "actionIndex"
        const val EXTRA_REMOTE_INPUT_KEY = "remoteInputKey"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXECUTE_NOTIFICATION_ACTION) return

        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)?.takeIf { it.isNotBlank() } ?: return
        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)?.takeIf { it.isNotBlank() } ?: return
        val actionIndex = intent.getIntExtra(EXTRA_ACTION_INDEX, -1)
        if (actionIndex < 0) return

        val remoteInputKey = intent.getStringExtra(EXTRA_REMOTE_INPUT_KEY)
        val replyText = if (!remoteInputKey.isNullOrBlank()) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(remoteInputKey)?.toString()
        } else {
            null
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val result = repository.executeRemoteAction(
                    channelId = channelId,
                    notificationId = notificationId,
                    actionIndex = actionIndex,
                    replyText = replyText
                )
                result.onSuccess { outcome ->
                    when (outcome) {
                        is CrossNotifyRepository.RemoteActionOutcome.Executed -> {
                            Log.i(TAG, "Executed remote action $actionIndex for $notificationId")
                        }
                        is CrossNotifyRepository.RemoteActionOutcome.AlreadyHandled -> {
                            showToast(appContext, "Notification was already handled")
                        }
                    }
                }.onFailure { err ->
                    Log.w(TAG, "Remote action failed for $notificationId: ${err.message}")
                    showToast(appContext, err.message ?: "Could not execute action")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
