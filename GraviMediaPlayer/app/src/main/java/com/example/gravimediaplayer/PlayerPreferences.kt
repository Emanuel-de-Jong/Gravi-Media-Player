package com.example.gravimediaplayer

import android.content.Context
import androidx.core.content.edit

class PlayerPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var rootUriString: String?
        get() = preferences.getString(KEY_ROOT_URI, null)
        set(value) {
            preferences.edit { putString(KEY_ROOT_URI, value) }
        }

    var playOrderMode: PlayOrderMode
        get() = loadMode(KEY_PLAY_ORDER_MODE, PlayOrderMode.IN_ORDER)
        set(value) {
            preferences.edit { putString(KEY_PLAY_ORDER_MODE, value.name) }
        }

    var loopMode: LoopMode
        get() = loadMode(KEY_LOOP_MODE, LoopMode.OFF)
        set(value) {
            preferences.edit { putString(KEY_LOOP_MODE, value.name) }
        }

    var genreSeparator: String
        get() = preferences.getString(KEY_GENRE_SEPARATOR, ";").orEmpty().ifBlank { ";" }
        set(value) {
            preferences.edit { putString(KEY_GENRE_SEPARATOR, value.ifBlank { ";" }) }
        }

    var showBrowserThumbnails: Boolean
        get() = preferences.getBoolean(KEY_SHOW_BROWSER_THUMBNAILS, false)
        set(value) {
            preferences.edit { putBoolean(KEY_SHOW_BROWSER_THUMBNAILS, value) }
        }

    var graviPickerSettings: GraviPickerSettings
        get() = GraviPickerSettings(
            depth = preferences.getInt(KEY_GRAVI_DEPTH, 2),
            parentOdds = preferences.getBoolean(KEY_GRAVI_PARENT_ODDS, false),
            childOdds = preferences.getBoolean(KEY_GRAVI_CHILD_ODDS, true),
            evenOddsMinFileCount = preferences.getInt(KEY_GRAVI_EVEN_ODDS_MIN_FILE_COUNT, 5),
            lessLikelyDivisor = preferences.getFloat(KEY_GRAVI_LESS_LIKELY_DIVISOR, 2f),
            queueEntries = preferences.getInt(KEY_GRAVI_QUEUE_ENTRIES, 100),
            edgeCaseFolderDepths = parseEdgeCaseFolderDepths(
                preferences.getString(KEY_GRAVI_EDGE_CASE_FOLDER_DEPTHS, "").orEmpty()
            ),
            blacklistFolders = parseBlacklistFolders(
                preferences.getString(KEY_GRAVI_BLACKLIST_FOLDERS, "").orEmpty()
            ),
        ).sanitized()
        set(value) {
            val safeValue = value.sanitized()
            preferences.edit {
                putInt(KEY_GRAVI_DEPTH, safeValue.depth)
                putBoolean(KEY_GRAVI_PARENT_ODDS, safeValue.parentOdds)
                putBoolean(KEY_GRAVI_CHILD_ODDS, safeValue.childOdds)
                putInt(KEY_GRAVI_EVEN_ODDS_MIN_FILE_COUNT, safeValue.evenOddsMinFileCount)
                putFloat(KEY_GRAVI_LESS_LIKELY_DIVISOR, safeValue.lessLikelyDivisor)
                putInt(KEY_GRAVI_QUEUE_ENTRIES, safeValue.queueEntries)
                putString(
                    KEY_GRAVI_EDGE_CASE_FOLDER_DEPTHS,
                    formatEdgeCaseFolderDepths(safeValue.edgeCaseFolderDepths)
                )
                putString(
                    KEY_GRAVI_BLACKLIST_FOLDERS,
                    safeValue.blacklistFolders.sorted().joinToString("\n")
                )
            }
        }

    fun resetSettingsExceptRootUri() {
        val rootUri = rootUriString
        preferences.edit { clear() }
        rootUriString = rootUri
    }

    private inline fun <reified Mode> loadMode(
        key: String,
        fallback: Mode
    ): Mode where Mode : Enum<Mode> {
        val modeName = preferences.getString(key, fallback.name)
        return enumValues<Mode>().firstOrNull { it.name == modeName } ?: fallback
    }

    private fun parseEdgeCaseFolderDepths(value: String): Map<String, Int> {
        return value.lines()
            .mapNotNull { line ->
                val parts = line.split('=', limit = 2)
                if (parts.size != 2) return@mapNotNull null

                val folderPath = parts[0].normalizedFolderPath()
                val depthIncrement = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                if (folderPath.isBlank() || depthIncrement <= 0) return@mapNotNull null
                folderPath to depthIncrement
            }
            .toMap()
    }

    private fun formatEdgeCaseFolderDepths(value: Map<String, Int>): String {
        return value.entries
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}=${it.value}" }
    }

    private fun parseBlacklistFolders(value: String): Set<String> {
        return value.lines()
            .map { it.normalizedFolderPath() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    companion object {
        private const val PREFERENCES_NAME = "gravi_media_player"
        private const val KEY_ROOT_URI = "root_uri"
        private const val KEY_PLAY_ORDER_MODE = "play_order_mode"
        private const val KEY_LOOP_MODE = "loop_mode"
        private const val KEY_GENRE_SEPARATOR = "genre_separator"
        private const val KEY_SHOW_BROWSER_THUMBNAILS = "show_browser_thumbnails"
        private const val KEY_GRAVI_DEPTH = "gravi_depth"
        private const val KEY_GRAVI_PARENT_ODDS = "gravi_parent_odds"
        private const val KEY_GRAVI_CHILD_ODDS = "gravi_child_odds"
        private const val KEY_GRAVI_EVEN_ODDS_MIN_FILE_COUNT = "gravi_even_odds_min_file_count"
        private const val KEY_GRAVI_LESS_LIKELY_DIVISOR = "gravi_less_likely_divisor"
        private const val KEY_GRAVI_QUEUE_ENTRIES = "gravi_queue_entries"
        private const val KEY_GRAVI_EDGE_CASE_FOLDER_DEPTHS = "gravi_edge_case_folder_depths"
        private const val KEY_GRAVI_BLACKLIST_FOLDERS = "gravi_blacklist_folders"
    }
}