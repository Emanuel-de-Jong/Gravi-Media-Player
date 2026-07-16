package com.example.gravimediaplayer

import android.content.Context

class PlayerPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var rootUriString: String?
        get() = preferences.getString(KEY_ROOT_URI, null)
        set(value) {
            preferences.edit().putString(KEY_ROOT_URI, value).apply()
        }

    var playOrderMode: PlayOrderMode
        get() = loadMode(KEY_PLAY_ORDER_MODE, PlayOrderMode.IN_ORDER)
        set(value) {
            preferences.edit().putString(KEY_PLAY_ORDER_MODE, value.name).apply()
        }

    var loopMode: LoopMode
        get() = loadMode(KEY_LOOP_MODE, LoopMode.OFF)
        set(value) {
            preferences.edit().putString(KEY_LOOP_MODE, value.name).apply()
        }

    private inline fun <reified Mode> loadMode(
        key: String,
        fallback: Mode
    ): Mode where Mode : Enum<Mode> {
        val modeName = preferences.getString(key, fallback.name)
        return enumValues<Mode>().firstOrNull { it.name == modeName } ?: fallback
    }

    companion object {
        private const val PREFERENCES_NAME = "gravi_media_player"
        private const val KEY_ROOT_URI = "root_uri"
        private const val KEY_PLAY_ORDER_MODE = "play_order_mode"
        private const val KEY_LOOP_MODE = "loop_mode"
    }
}