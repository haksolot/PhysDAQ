package com.physdaq.mobile.nodes

import android.content.Context

/** [KeyValueStore] on top of SharedPreferences. */
class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences("physdaq", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }
}
