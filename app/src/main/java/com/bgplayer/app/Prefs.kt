package com.bgplayer.app

import android.content.Context
import android.content.SharedPreferences
import com.bgplayer.core.BgLog

/**
 * Persistent settings. Versioned: when SCHEMA changes, everything stored by an older
 * version is discarded instead of being misread.
 */
object Prefs {

    /** Bump when the meaning/format of stored values changes. */
    const val SCHEMA = 1

    private const val KEY_SCHEMA = "schema"
    private const val KEY_LOG_LEVEL = "log_level"
    private const val KEY_LOOP = "loop_one"
    private const val KEY_LAST_FOLDER = "last_folder"
    private const val KEY_LAST_TIMER_MIN = "last_timer_min"

    private lateinit var sp: SharedPreferences

    /** @return true if old settings were discarded because of a schema change. */
    fun init(context: Context): Boolean {
        sp = context.getSharedPreferences("bgplayer", Context.MODE_PRIVATE)
        val stored = sp.getInt(KEY_SCHEMA, -1)
        if (stored != SCHEMA) {
            val hadData = sp.all.isNotEmpty()
            sp.edit().clear().putInt(KEY_SCHEMA, SCHEMA).apply()
            return hadData
        }
        return false
    }

    var logLevel: Int
        get() = sp.getInt(KEY_LOG_LEVEL, BgLog.DEFAULT_LEVEL)
        set(v) = sp.edit().putInt(KEY_LOG_LEVEL, v).apply()

    /** Repeat the current video. On by default (the app's main use case). */
    var loop: Boolean
        get() = sp.getBoolean(KEY_LOOP, true)
        set(v) = sp.edit().putBoolean(KEY_LOOP, v).apply()

    var lastFolder: String?
        get() = sp.getString(KEY_LAST_FOLDER, null)
        set(v) = sp.edit().putString(KEY_LAST_FOLDER, v).apply()

    var lastTimerMinutes: Int
        get() = sp.getInt(KEY_LAST_TIMER_MIN, 30)
        set(v) = sp.edit().putInt(KEY_LAST_TIMER_MIN, v).apply()
}
