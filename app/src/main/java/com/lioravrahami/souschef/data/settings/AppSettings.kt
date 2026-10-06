package com.lioravrahami.souschef.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * User settings. Plain values live in SharedPreferences (included in Android backups);
 * the API key lives in the device-bound [SecretStore].
 *
 * [changes] ticks on every write so Compose screens can re-read values.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    var openRouterApiKey: String
        get() = secrets.get(KEY_API_KEY) ?: ""
        set(value) { secrets.put(KEY_API_KEY, value.trim()); bump() }

    val hasApiKey: Boolean get() = openRouterApiKey.isNotBlank()

    var openRouterModel: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        set(value) { prefs.edit().putString(KEY_MODEL, value.trim()).apply(); bump() }

    /** Relative size of a classical tweak (0.05 = timid, 0.5 = wild). */
    var boldness: Float
        get() = prefs.getFloat(KEY_BOLDNESS, DEFAULT_BOLDNESS)
        set(value) { prefs.edit().putFloat(KEY_BOLDNESS, value.coerceIn(0.02f, 0.8f)).apply(); bump() }

    /** Probability that an exploration run makes a global jump instead of a local tweak. */
    var explorationRate: Float
        get() = prefs.getFloat(KEY_EXPLORATION, DEFAULT_EXPLORATION)
        set(value) { prefs.edit().putFloat(KEY_EXPLORATION, value.coerceIn(0f, 1f)).apply(); bump() }

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) { prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply(); bump() }

    /** How long the alarm keeps ringing if nobody stops it. */
    var alarmMaxSeconds: Int
        get() = prefs.getInt(KEY_ALARM_MAX, 300)
        set(value) { prefs.edit().putInt(KEY_ALARM_MAX, value.coerceIn(10, 3600)).apply(); bump() }

    private fun bump() { _changes.value = _changes.value + 1 }

    companion object {
        const val PREFS_NAME = "souschef_settings"
        const val DEFAULT_MODEL = "google/gemini-2.5-flash-lite"
        const val DEFAULT_BOLDNESS = 0.15f
        const val DEFAULT_EXPLORATION = 0.25f
        private const val KEY_API_KEY = "openrouter_api_key"
        private const val KEY_MODEL = "openrouter_model"
        private const val KEY_BOLDNESS = "boldness"
        private const val KEY_EXPLORATION = "exploration_rate"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_ALARM_MAX = "alarm_max_seconds"
    }
}
