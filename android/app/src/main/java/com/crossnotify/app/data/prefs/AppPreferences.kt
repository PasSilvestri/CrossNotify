package com.crossnotify.app.data.prefs

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

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
}
