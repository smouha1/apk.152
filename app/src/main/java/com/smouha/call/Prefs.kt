package com.smouha.call

import android.content.Context
import java.util.HashSet
import java.util.UUID

/**
 * Persistent bridge identity + multiple session tokens (multi-laptop / multi-extension)
 * + theme preference. Uses defensive HashSet copies to avoid Android SharedPreferences StringSet mutation bugs.
 */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("smouha_call", Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            var id = sp.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrBlank()) {
                id = "SC-" + UUID.randomUUID().toString().take(8).uppercase()
                sp.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }

    /** Display name shown in extension (e.g. ds60-phone1) */
    var deviceName: String
        get() = sp.getString(KEY_DEVICE_NAME, null) ?: DEFAULT_DEVICE_NAME
        set(v) = sp.edit().putString(KEY_DEVICE_NAME, v.trim().ifBlank { DEFAULT_DEVICE_NAME }).apply()

    /** Latest token (compat) */
    var sessionToken: String?
        get() = sp.getString(KEY_TOKEN, null)
        set(v) {
            sp.edit().putString(KEY_TOKEN, v).apply()
            if (!v.isNullOrBlank()) addSessionToken(v)
        }

    fun sessionTokens(): Set<String> {
        val raw = sp.getStringSet(KEY_TOKENS, null)
        val copy = if (raw != null) HashSet<String>(raw) else HashSet<String>()
        val latest = sp.getString(KEY_TOKEN, null)
        if (!latest.isNullOrBlank()) copy.add(latest)
        return copy
    }

    fun hasPairedSession(): Boolean = sessionTokens().isNotEmpty()

    fun addSessionToken(token: String) {
        val set = HashSet<String>(sessionTokens())
        set.add(token)
        while (set.size > 12) {
            val first = set.firstOrNull { it != token } ?: break
            set.remove(first)
        }
        sp.edit()
            .putStringSet(KEY_TOKENS, HashSet<String>(set))
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun isValidSessionToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return sessionTokens().contains(token)
    }

    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTO_START, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_START, v).apply()

    var darkTheme: Boolean
        get() = sp.getBoolean(KEY_DARK_THEME, false)
        set(v) = sp.edit().putBoolean(KEY_DARK_THEME, v).apply()

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_TOKEN = "session_token"
        private const val KEY_TOKENS = "session_tokens"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_DARK_THEME = "dark_theme"
        const val PORT = 8765
        /** Fixed team branding phrase (not the rotating pair secret) */
        const val BRAND_TAG = "ds60ashrafamin"
        const val DEFAULT_DEVICE_NAME = "ds60-phone1"
        /** Pairing secret lifetime */
        const val PAIR_SECRET_TTL_MS = 10 * 60 * 1000L
    }
}
