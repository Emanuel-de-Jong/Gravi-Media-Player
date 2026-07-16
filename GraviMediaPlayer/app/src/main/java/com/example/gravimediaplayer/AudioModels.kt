package com.example.gravimediaplayer

import android.net.Uri

data class AudioItem(
    val uriString: String,
    val title: String,
    val folderPath: String,
    val tags: List<String> = emptyList(),
) {
    val uri: Uri
        get() = Uri.parse(uriString)
}

data class PlaybackSnapshot(
    val queue: List<AudioItem> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val playOrderMode: PlayOrderMode = PlayOrderMode.IN_ORDER,
    val loopMode: LoopMode = LoopMode.OFF,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val errorMessage: String? = null,
) {
    val currentItem: AudioItem?
        get() = queue.getOrNull(currentIndex)
}

interface ModeLabel {
    val label: String
}

enum class PlayOrderMode(
    override val label: String,
) : ModeLabel {
    IN_ORDER("In order"),
    SHUFFLE("Shuffle"),
}

enum class LoopMode(
    override val label: String,
) : ModeLabel {
    OFF("No repeat"),
    SONG("Repeat song"),
    QUEUE("Repeat queue"),
}

data class BrowserEntry(
    val name: String,
    val uriString: String,
    val isDirectory: Boolean,
    val audioItem: AudioItem? = null,
    val trackCount: Int? = null,
)

data class TagGroup(
    val name: String,
    val items: List<AudioItem>,
)