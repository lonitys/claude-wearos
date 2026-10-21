package com.lonitys.claudewatch

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("claude_watch", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", null) ?: BuildConfig.DEFAULT_URL
        set(value) = sp.edit().putString("url", value.trim().trimEnd('/')).apply()

    var token: String
        get() = sp.getString("token", null) ?: BuildConfig.DEFAULT_TOKEN
        set(value) = sp.edit().putString("token", value.trim()).apply()

    /** Whether the background connection should be running. */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(value) = sp.edit().putBoolean("enabled", value).apply()

    var shakeEnabled: Boolean
        get() = sp.getBoolean("shake", true)
        set(value) = sp.edit().putBoolean("shake", value).apply()

    val configured: Boolean get() = url.isNotBlank() && token.isNotBlank()

    fun client() = RelayClient(url, token)
}
