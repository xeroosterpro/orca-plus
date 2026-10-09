package com.wholphinplus.sources

import android.content.Context

/**
 * One-time changes to settings for installs from before a new default (a default only reaches new
 * installs: Wholphin writes its settings once, at the first start). Each [key] runs once per TV;
 * after that the person's own choice stands.
 */
object OrcaDefaults {
    fun firstTime(
        context: Context,
        key: String,
    ): Boolean {
        val prefs = context.getSharedPreferences("wholphinplus_defaults", Context.MODE_PRIVATE)
        if (prefs.getBoolean(key, false)) return false
        prefs.edit().putBoolean(key, true).apply()
        return true
    }
}
