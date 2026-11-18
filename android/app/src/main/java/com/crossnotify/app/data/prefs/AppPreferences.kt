package com.crossnotify.app.data.prefs

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class SilencedAppRule(
    val appIdentifier: String,
    val appLabel: String,
    val silencedUntilMs: Long
)

/**
 * Stores non-sensitive UI preferences (device display name and insecure HTTP prompt policy).
 * All cryptographic secrets live exclusively in Room wrapped by [com.crossnotify.app.crypto.CryptoManager].
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("crossnotify_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_ALLOW_INSECURE_HTTP_DEFAULT = "allow_insecure_http_default"
        private const val KEY_DAILY_SENT_DATE = "daily_sent_date"
        private const val KEY_DAILY_SENT_PREFIX = "daily_sent_count_"
        private const val KEY_SILENCE_CHANNEL_PREFIX = "silence_channel_"
        private const val KEY_SILENCE_APPS_PREFIX = "silence_apps_"

        fun defaultDeviceName(): String {
            val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: ""
            val model = Build.MODEL ?: "Android Device"
            return if (model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else {
                "$manufacturer $model".trim()
            }
        }
    }

    private val _deviceName = MutableStateFlow(
        prefs.getString(KEY_DEVICE_NAME, null) ?: defaultDeviceName()
    )
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _allowInsecureHttpByDefault = MutableStateFlow(
        prefs.getBoolean(KEY_ALLOW_INSECURE_HTTP_DEFAULT, false)
    )
    val allowInsecureHttpByDefault: StateFlow<Boolean> = _allowInsecureHttpByDefault.asStateFlow()

    private val _silenceVersion = MutableStateFlow(0)
    val silenceVersion: StateFlow<Int> = _silenceVersion.asStateFlow()

    fun setDeviceName(name: String) {
        val trimmed = name.trim().ifEmpty { defaultDeviceName() }
        prefs.edit().putString(KEY_DEVICE_NAME, trimmed).apply()
        _deviceName.value = trimmed
    }

    fun setAllowInsecureHttpByDefault(allow: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_INSECURE_HTTP_DEFAULT, allow).apply()
        _allowInsecureHttpByDefault.value = allow
    }

    @Synchronized
    fun incrementTodayRelayCount(channelId: String) {
        val today = java.time.LocalDate.now().toString()
        val storedDate = prefs.getString(KEY_DAILY_SENT_DATE, null)
        val editor = prefs.edit()
        if (storedDate != today) {
            prefs.all.keys.filter { it.startsWith(KEY_DAILY_SENT_PREFIX) }.forEach {
                editor.remove(it)
            }
            editor.putString(KEY_DAILY_SENT_DATE, today)
            editor.putInt(KEY_DAILY_SENT_PREFIX + channelId, 1)
        } else {
            val current = prefs.getInt(KEY_DAILY_SENT_PREFIX + channelId, 0)
            editor.putInt(KEY_DAILY_SENT_PREFIX + channelId, current + 1)
        }
        editor.apply()
    }

    fun getTodayRelayCount(channelId: String): Int {
        val today = java.time.LocalDate.now().toString()
        val storedDate = prefs.getString(KEY_DAILY_SENT_DATE, null)
        if (storedDate != today) return 0
        return prefs.getInt(KEY_DAILY_SENT_PREFIX + channelId, 0)
    }

    fun getRelayLastFcmToken(channelId: String): String? {
        return prefs.getString("relay_fcm_token_$channelId", null)
    }

    fun setRelayLastFcmToken(channelId: String, token: String) {
        prefs.edit().putString("relay_fcm_token_$channelId", token).apply()
    }

    @Synchronized
    fun setChannelSilence(channelId: String, durationMs: Long) {
        if (durationMs <= 0L) {
            clearChannelSilence(channelId)
            return
        }
        val untilMs = System.currentTimeMillis() + durationMs
        prefs.edit().putLong(KEY_SILENCE_CHANNEL_PREFIX + channelId, untilMs).apply()
        _silenceVersion.value += 1
    }

    @Synchronized
    fun clearChannelSilence(channelId: String) {
        prefs.edit().remove(KEY_SILENCE_CHANNEL_PREFIX + channelId).apply()
        _silenceVersion.value += 1
    }

    fun getChannelSilenceUntil(channelId: String): Long? {
        val untilMs = prefs.getLong(KEY_SILENCE_CHANNEL_PREFIX + channelId, 0L)
        if (untilMs <= System.currentTimeMillis()) {
            if (untilMs > 0L) {
                prefs.edit().remove(KEY_SILENCE_CHANNEL_PREFIX + channelId).apply()
            }
            return null
        }
        return untilMs
    }

    @Synchronized
    fun setAppSilence(
        channelId: String,
        appIdentifier: String,
        appLabel: String,
        durationMs: Long
    ) {
        val trimmedId = appIdentifier.trim()
        if (trimmedId.isEmpty() || durationMs <= 0L) return
        val now = System.currentTimeMillis()
        val untilMs = now + durationMs
        val current = getSilencedAppsForChannel(channelId)
            .filterNot { it.appIdentifier.equals(trimmedId, ignoreCase = true) }
            .toMutableList()
        current.add(
            SilencedAppRule(
                appIdentifier = trimmedId,
                appLabel = appLabel.trim().ifEmpty { trimmedId },
                silencedUntilMs = untilMs
            )
        )
        saveSilencedAppsForChannel(channelId, current)
        _silenceVersion.value += 1
    }

    @Synchronized
    fun clearAppSilence(channelId: String, appIdentifier: String) {
        val updated = getSilencedAppsForChannel(channelId)
            .filterNot { it.appIdentifier.equals(appIdentifier, ignoreCase = true) }
        saveSilencedAppsForChannel(channelId, updated)
        _silenceVersion.value += 1
    }

    @Synchronized
    fun clearAllSilencesForChannel(channelId: String) {
        prefs.edit()
            .remove(KEY_SILENCE_CHANNEL_PREFIX + channelId)
            .remove(KEY_SILENCE_APPS_PREFIX + channelId)
            .apply()
        _silenceVersion.value += 1
    }

    fun getSilencedAppsForChannel(channelId: String): List<SilencedAppRule> {
        val raw = prefs.getString(KEY_SILENCE_APPS_PREFIX + channelId, null) ?: return emptyList()
        val now = System.currentTimeMillis()
        val active = mutableListOf<SilencedAppRule>()
        var hadExpired = false
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                val label = obj.optString("label", id).trim().ifEmpty { id }
                val until = obj.optLong("until", 0L)
                if (id.isNotEmpty() && until > now) {
                    active.add(SilencedAppRule(id, label, until))
                } else {
                    hadExpired = true
                }
            }
        }
        if (hadExpired) {
            saveSilencedAppsForChannel(channelId, active)
        }
        return active
    }

    fun isNotificationSilenced(
        channelId: String,
        packageName: String?,
        appName: String?
    ): Boolean {
        if (getChannelSilenceUntil(channelId) != null) return true
        val silencedApps = getSilencedAppsForChannel(channelId)
        if (silencedApps.isEmpty()) return false
        val pkg = packageName?.trim().orEmpty()
        val app = appName?.trim().orEmpty()
        return silencedApps.any { rule ->
            (pkg.isNotEmpty() && rule.appIdentifier.equals(pkg, ignoreCase = true)) ||
                (app.isNotEmpty() && (rule.appIdentifier.equals(app, ignoreCase = true) ||
                    rule.appLabel.equals(app, ignoreCase = true)))
        }
    }

    private fun saveSilencedAppsForChannel(channelId: String, rules: List<SilencedAppRule>) {
        if (rules.isEmpty()) {
            prefs.edit().remove(KEY_SILENCE_APPS_PREFIX + channelId).apply()
            return
        }
        val arr = JSONArray()
        for (rule in rules) {
            arr.put(
                JSONObject().apply {
                    put("id", rule.appIdentifier)
                    put("label", rule.appLabel)
                    put("until", rule.silencedUntilMs)
                }
            )
        }
        prefs.edit().putString(KEY_SILENCE_APPS_PREFIX + channelId, arr.toString()).apply()
    }
}
