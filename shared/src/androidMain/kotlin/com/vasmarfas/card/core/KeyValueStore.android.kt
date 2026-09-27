package com.vasmarfas.card.core

import android.content.Context
import android.content.SharedPreferences

private class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

actual fun createKeyValueStore(): KeyValueStore =
    PrefsStore(AppContextHolder.context.getSharedPreferences("vasmarfas", Context.MODE_PRIVATE))
