package com.example.gravimediaplayer

fun AudioItem.queueContext(): String {
    return folderPath
        .ifBlank { "No folder or tag info" }
}

fun AudioItem.compactAudioInfo(): String {
    val parts = listOfNotNull(
        mimeType?.substringAfterLast('/')?.uppercase(),
        bitrate?.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
    )
    return parts.ifEmpty { listOf("Hi-Fi path") }.joinToString(" · ")
}

fun formatTime(milliseconds: Int): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

fun formatTrackCount(trackCount: Int): String {
    return if (trackCount == 1) "1 track" else "$trackCount tracks"
}