package com.example.gravimediaplayer

import android.net.Uri

data class AudioItem(
    val uriString: String,
    val title: String,
    val folderPath: String,
) {
    val uri: Uri
        get() = Uri.parse(uriString)
}

data class PlaybackSnapshot(
    val queue: List<AudioItem> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val loopEnabled: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val errorMessage: String? = null,
) {
    val currentItem: AudioItem?
        get() = queue.getOrNull(currentIndex)
}

data class BrowserEntry(
    val name: String,
    val uriString: String,
    val isDirectory: Boolean,
    val audioItem: AudioItem? = null,
)