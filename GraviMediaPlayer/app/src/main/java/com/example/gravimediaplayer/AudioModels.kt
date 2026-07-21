package com.example.gravimediaplayer

import android.net.Uri
import androidx.core.net.toUri

data class AudioItem(
    val uriString: String,
    val title: String,
    val folderPath: String,
    val tags: List<String> = emptyList(),
    val artworkUriString: String? = null,
    val mimeType: String? = null,
    val bitrate: Int? = null,
    val durationMs: Long? = null,
    val artist: String? = null,
    val releaseDate: String? = null,
    val lyrics: String? = null,
    val lastModifiedMs: Long = 0,
) {
    val uri: Uri
        get() = uriString.toUri()

    val displayTitle: String
        get() = title.substringBeforeLast('.', title)
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

data class PendingPlaybackRequest(
    val queue: List<AudioItem>,
    val startIndex: Int,
    val queueTitle: String,
    val playOrderMode: PlayOrderMode,
)

data class PlaybackSnapshot(
    val queue: List<AudioItem> = emptyList(),
    val queueTitle: String? = null,
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val playOrderMode: PlayOrderMode = PlayOrderMode.IN_ORDER,
    val loopMode: LoopMode = LoopMode.QUEUE,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val audioInfoText: String? = null,
    val errorMessage: String? = null,
) {
    val currentItem: AudioItem?
        get() = queue.getOrNull(currentIndex)
}

enum class PlayOrderMode(
    val label: String,
) {
    IN_ORDER("In order"),
    SHUFFLE("Shuffle"),
    GRAVI_SHUFFLE("Gravi shuffle"),
}

fun PlayOrderMode.next(): PlayOrderMode {
    return when (this) {
        PlayOrderMode.IN_ORDER -> PlayOrderMode.SHUFFLE
        PlayOrderMode.SHUFFLE -> PlayOrderMode.GRAVI_SHUFFLE
        PlayOrderMode.GRAVI_SHUFFLE -> PlayOrderMode.IN_ORDER
    }
}

enum class LoopMode(
    val label: String,
) {
    OFF("No repeat"),
    SONG("Repeat song"),
    QUEUE("Repeat queue"),
}

enum class BrowserSortMode(
    val label: String,
) {
    FILENAME("Name"),
    ARTIST("Artist"),
    TITLE("Title"),
    DURATION("Duration"),
    RELEASE_DATE("Release date"),
    ADDITION_DATE("Addition date"),
}

fun LoopMode.next(): LoopMode {
    return when (this) {
        LoopMode.OFF -> LoopMode.QUEUE
        LoopMode.QUEUE -> LoopMode.SONG
        LoopMode.SONG -> LoopMode.OFF
    }
}