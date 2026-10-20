package com.crossnotify.app.firebase

import android.content.Context
import android.util.Log
import com.crossnotify.app.data.db.ServerDao
import com.crossnotify.app.data.db.ServerEntity
import com.crossnotify.app.qr.QrFirebaseInfo
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages runtime initialization of FirebaseApp instances across multiple self-hosted servers.
 *
 * Supports dynamic multi-project FCM without compile-time configuration:
 * 1. The APK contains no static google-services.json file and disables FirebaseInitProvider.
 * 2. When pairing with the first server (or when no default instance exists), initializes the project
 *    as both `[DEFAULT]` and a named instance (`cn_${projectId}`) to ensure internal Google Play Services
 *    and Firebase SDK calls succeed without throwing an uninitialized IllegalStateException.
 * 3. Subsequent servers are initialized as isolated named apps (`cn_${projectId}`).
 * 4. When a server backing the default app is removed, automatically promotes the next active server to `[DEFAULT]`.
 */
@Singleton
class FirebaseProjectManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverDao: ServerDao
) {
    companion object {
        private const val TAG = "FirebaseProjectManager"
        fun appNameForProject(projectId: String): String = "cn_$projectId"
    }

    /**
     * Re-initializes all stored servers from Room on application startup.
     */
    suspend fun initializeAllFromDatabase() {
        val servers = serverDao.getAll()
        if (servers.isEmpty()) return

        synchronized(this) {
            servers.forEach { server ->
                ensureFirebaseAppInitializedInternal(
                    projectId = server.fbProjectId,
                    appId = server.fbAppId,
                    apiKey = server.fbApiKey,
                    senderId = server.fbSenderId
                )
            }
        }
    }

    /**
     * Ensures the Firebase project described by [fb] is initialized at runtime
     * (including `[DEFAULT]` fallback if no default app exists yet).
     */
    fun ensureProjectInitialized(fb: QrFirebaseInfo): FirebaseApp {
        synchronized(this) {
            return ensureFirebaseAppInitializedInternal(
                projectId = fb.projectId,
                appId = fb.appId,
                apiKey = fb.apiKey,
                senderId = fb.senderId
            )
        }
    }

    /**
     * Ensures the Firebase project for [server] is initialized at runtime.
     */
    fun ensureServerInitialized(server: ServerEntity): FirebaseApp {
        synchronized(this) {
            return ensureFirebaseAppInitializedInternal(
                projectId = server.fbProjectId,
                appId = server.fbAppId,
                apiKey = server.fbApiKey,
                senderId = server.fbSenderId
            )
        }
    }

    /**
     * Fetches the FCM registration token for the named FirebaseApp corresponding to [server].
     */
    suspend fun getFcmToken(server: ServerEntity): String {
        val app = ensureServerInitialized(server)
        val messaging = app.get(FirebaseMessaging::class.java)
        return messaging.token.await()
    }

    /**
     * Fetches the FCM registration token for the Firebase project in [fb].
     */
    suspend fun getFcmToken(fb: QrFirebaseInfo): String {
        val app = ensureProjectInitialized(fb)
        val messaging = app.get(FirebaseMessaging::class.java)
        return messaging.token.await()
    }

    /**
     * Reconciles active FirebaseApp instances after a server or channel is removed.
     * If the project backing `[DEFAULT]` is no longer referenced by any server in Room,
     * deletes `[DEFAULT]` and promotes the next remaining server to `[DEFAULT]`.
     */
    suspend fun reconcileAfterServerRemoval() {
        val remainingServers = serverDao.getAll()
        val activeProjectIds = remainingServers.map { it.fbProjectId }.toSet()

        synchronized(this) {
            val runningApps = FirebaseApp.getApps(context)

            // Delete named apps whose projectId is no longer in Room
            for (app in runningApps) {
                if (app.name.startsWith("cn_")) {
                    val projId = app.options.projectId
                    if (projId != null && projId !in activeProjectIds) {
                        runCatching { app.delete() }
                    }
                }
            }

            // Check if [DEFAULT] needs promotion
            val defaultApp = runningApps.firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            if (defaultApp != null) {
                val defaultProjectId = defaultApp.options.projectId
                if (defaultProjectId == null || defaultProjectId !in activeProjectIds) {
                    runCatching { defaultApp.delete() }
                    val nextServer = remainingServers.firstOrNull()
                    if (nextServer != null) {
                        val options = buildOptions(
                            projectId = nextServer.fbProjectId,
                            appId = nextServer.fbAppId,
                            apiKey = nextServer.fbApiKey,
                            senderId = nextServer.fbSenderId
                        )
                        runCatching {
                            FirebaseApp.initializeApp(context, options)
                            Log.i(TAG, "Promoted project ${nextServer.fbProjectId} to [DEFAULT] FirebaseApp")
                        }
                    }
                }
            } else if (remainingServers.isNotEmpty()) {
                val first = remainingServers.first()
                ensureFirebaseAppInitializedInternal(
                    projectId = first.fbProjectId,
                    appId = first.fbAppId,
                    apiKey = first.fbApiKey,
                    senderId = first.fbSenderId
                )
            }
        }
    }

    private fun ensureFirebaseAppInitializedInternal(
        projectId: String,
        appId: String,
        apiKey: String,
        senderId: String
    ): FirebaseApp {
        val options = buildOptions(projectId, appId, apiKey, senderId)
        val existingApps = FirebaseApp.getApps(context)

        // If no default FirebaseApp exists yet, register this first project as [DEFAULT]
        // to satisfy internal Play Services components that query the default instance.
        val hasDefault = existingApps.any { it.name == FirebaseApp.DEFAULT_APP_NAME }
        if (!hasDefault) {
            runCatching {
                FirebaseApp.initializeApp(context, options)
                Log.i(TAG, "Initialized [DEFAULT] FirebaseApp with project $projectId")
            }.onFailure { err ->
                Log.w(TAG, "Failed to initialize [DEFAULT] FirebaseApp: ${err.message}")
            }
        }

        val namedAppName = appNameForProject(projectId)
        val existingNamed = FirebaseApp.getApps(context).firstOrNull { it.name == namedAppName }
        if (existingNamed != null) {
            // Check if parameters changed for the same projectId
            if (
                existingNamed.options.applicationId == appId &&
                existingNamed.options.apiKey == apiKey &&
                existingNamed.options.gcmSenderId == senderId
            ) {
                return existingNamed
            }
            runCatching { existingNamed.delete() }
        }

        return FirebaseApp.initializeApp(context, options, namedAppName)
    }

    private fun buildOptions(
        projectId: String,
        appId: String,
        apiKey: String,
        senderId: String
    ): FirebaseOptions {
        return FirebaseOptions.Builder()
            .setProjectId(projectId)
            .setApplicationId(appId)
            .setApiKey(apiKey)
            .setGcmSenderId(senderId)
            .build()
    }
}
