package com.crossnotify.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds live in-memory references to relayed [Notification.Action] objects (including their
 * non-serializable [PendingIntent]s and [RemoteInput]s) and maps Android `sbn.key` values to
 * CrossNotify `(channelId, notificationId)` pairs so dismissals and remote actions stay in sync.
 */
@Singleton
class RelayNotificationRegistry @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class CachedRelayedNotification(
        val channelId: String,
        val notificationId: String,
        val sbnKey: String,
        val actions: List<Notification.Action>,
        val createdAt: Long = System.currentTimeMillis()
    )

    sealed class ActionExecutionResult {
        data object Executed : ActionExecutionResult()
        data class Failed(val reason: String) : ActionExecutionResult()
    }

    companion object {
        private const val TAG = "RelayNotifRegistry"
        private const val MAX_ENTRY_AGE_MS = 24 * 60 * 60 * 1000L
        private const val CANCEL_AFTER_ACTION_DELAY_MS = 350L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // notificationId -> CachedRelayedNotification
    private val byNotificationId = ConcurrentHashMap<String, CachedRelayedNotification>()

    // sbnKey -> set of (channelId, notificationId)
    private val bySbnKey = ConcurrentHashMap<String, MutableSet<Pair<String, String>>>()

    @Volatile
    private var cancelCallback: ((String) -> Unit)? = null

    fun setCancelCallback(callback: ((String) -> Unit)?) {
        cancelCallback = callback
    }

    fun register(
        channelId: String,
        notificationId: String,
        sbnKey: String,
        actions: List<Notification.Action>
    ) {
        pruneStaleEntries()
        val entry = CachedRelayedNotification(
            channelId = channelId,
            notificationId = notificationId,
            sbnKey = sbnKey,
            actions = actions
        )
        byNotificationId[notificationId] = entry
        val set = bySbnKey.computeIfAbsent(sbnKey) {
            Collections.newSetFromMap(ConcurrentHashMap())
        }
        set.add(channelId to notificationId)
    }

    /**
     * Called when Android removes the original notification from the relay status bar.
     * Removes and returns all tracked `(channelId, notificationId)` pairs for [sbnKey].
     */
    fun consumeBySbnKey(sbnKey: String): List<Pair<String, String>> {
        val removedSet = bySbnKey.remove(sbnKey) ?: return emptyList()
        val result = removedSet.toList()
        for ((_, notificationId) in result) {
            byNotificationId.remove(notificationId)
        }
        return result
    }

    /**
     * Executes a cached [Notification.Action] on the relay device on behalf of a subscriber,
     * populating [RemoteInput] results if [replyText] is supplied, and cancels the original
     * notification on the relay phone so it disappears across the ecosystem.
     */
    fun executeAction(
        notificationId: String,
        actionIndex: Int,
        replyText: String?
    ): ActionExecutionResult {
        val entry = byNotificationId[notificationId]
            ?: return ActionExecutionResult.Failed("Notification is no longer active on relay device")

        val action = entry.actions.getOrNull(actionIndex)
            ?: return ActionExecutionResult.Failed("Action index $actionIndex is not available")

        val pendingIntent = action.actionIntent
            ?: return ActionExecutionResult.Failed("Action has no PendingIntent")

        return try {
            val remoteInputs = action.remoteInputs
            if (!remoteInputs.isNullOrEmpty() && replyText != null) {
                val fillInIntent = Intent()
                val resultsBundle = Bundle()
                for (ri in remoteInputs) {
                    resultsBundle.putCharSequence(ri.resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, resultsBundle)
                pendingIntent.send(context, 0, fillInIntent)
            } else {
                pendingIntent.send()
            }

            // Clean up tracking immediately so cancelling the notification does not trigger
            // a redundant dismissal broadcast back to subscribers.
            val sbnKey = entry.sbnKey
            val removedPeers = bySbnKey.remove(sbnKey)
            removedPeers?.forEach { (_, id) -> byNotificationId.remove(id) }
            byNotificationId.remove(notificationId)

            // Dismiss the original notification on the relay phone after giving the target app
            // a brief moment to consume the PendingIntent / RemoteInput broadcast.
            val cancelFn = cancelCallback
            if (cancelFn != null) {
                scope.launch {
                    delay(CANCEL_AFTER_ACTION_DELAY_MS)
                    runCatching { cancelFn(sbnKey) }
                        .onFailure { err -> Log.w(TAG, "Failed to cancel relay notification $sbnKey: ${err.message}") }
                }
            }

            ActionExecutionResult.Executed
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "PendingIntent was canceled for notification $notificationId: ${e.message}")
            byNotificationId.remove(notificationId)
            ActionExecutionResult.Failed("Notification action expired on relay device")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing action $actionIndex for notification $notificationId", e)
            ActionExecutionResult.Failed(e.message ?: "Failed to execute action")
        }
    }

    private fun pruneStaleEntries() {
        val cutoff = System.currentTimeMillis() - MAX_ENTRY_AGE_MS
        val iterator = byNotificationId.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.createdAt < cutoff) {
                iterator.remove()
                bySbnKey[entry.value.sbnKey]?.remove(entry.value.channelId to entry.key)
            }
        }
        val keyIterator = bySbnKey.entries.iterator()
        while (keyIterator.hasNext()) {
            if (keyIterator.next().value.isEmpty()) {
                keyIterator.remove()
            }
        }
    }
}
