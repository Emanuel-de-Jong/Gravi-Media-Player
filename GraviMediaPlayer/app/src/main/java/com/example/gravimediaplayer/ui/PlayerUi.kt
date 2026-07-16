package com.example.gravimediaplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.gravimediaplayer.LoopMode
import com.example.gravimediaplayer.PlayOrderMode
import com.example.gravimediaplayer.PlaybackSnapshot
import com.example.gravimediaplayer.formatTime
import com.example.gravimediaplayer.formatTrackCount
import com.example.gravimediaplayer.next
import com.example.gravimediaplayer.queueContext
import com.example.gravimediaplayer.ui.theme.GraviMediaPlayerTheme

@Composable
fun MiniPlayer(
    snapshot: PlaybackSnapshot,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val item = snapshot.currentItem ?: return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable(onClick = onExpand),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    snapshot.queueTitle ?: item.folderPath.ifBlank { "Root music folder" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (snapshot.isPlaying) "Pause" else "Play",
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
            }
        }
    }
}

@Composable
fun PlayScreen(
    snapshot: PlaybackSnapshot,
    expanded: Boolean,
    onCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Int) -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onPlayOrderModeChanged: (PlayOrderMode) -> Unit,
    onLoopModeChanged: (LoopMode) -> Unit,
) {
    val item = snapshot.currentItem

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCollapse, enabled = expanded) {
                Icon(Icons.Filled.ExpandLess, contentDescription = "Collapse player")
            }
            Text(
                "Now playing",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Card(
            modifier = Modifier.size(220.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("♪", style = MaterialTheme.typography.displayLarge)
            }
        }
        Text(
            text = item?.title ?: "Nothing playing",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = snapshot.queueTitle ?: item?.folderPath ?: "Select a file or folder to begin.",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Slider(
            value = snapshot.positionMs.toFloat()
                .coerceIn(0f, snapshot.durationMs.toFloat().coerceAtLeast(1f)),
            onValueChange = { onSeek(it.toInt()) },
            valueRange = 0f..snapshot.durationMs.toFloat().coerceAtLeast(1f),
            enabled = snapshot.durationMs > 0,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(snapshot.positionMs))
            Text(formatTime(snapshot.durationMs))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, enabled = item != null) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
            }
            IconButton(
                onClick = onPlayPause,
                enabled = item != null,
                modifier = Modifier.size(72.dp),
            ) {
                Icon(
                    if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (snapshot.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(56.dp),
                )
            }
            IconButton(onClick = onNext, enabled = item != null) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
            }
        }
        PlaybackModeRow(
            snapshot = snapshot,
            onPlayOrderModeChanged = onPlayOrderModeChanged,
            onLoopModeChanged = onLoopModeChanged,
        )
        if (snapshot.errorMessage != null) {
            Text(snapshot.errorMessage, color = MaterialTheme.colorScheme.error)
        }
        QueueList(
            snapshot = snapshot,
            onPlayQueueIndex = onPlayQueueIndex,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PlaybackModeRow(
    snapshot: PlaybackSnapshot,
    onPlayOrderModeChanged: (PlayOrderMode) -> Unit,
    onLoopModeChanged: (LoopMode) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onPlayOrderModeChanged(snapshot.playOrderMode.next()) }) {
            Icon(
                Icons.Filled.Shuffle,
                contentDescription = snapshot.playOrderMode.label,
                tint = if (snapshot.playOrderMode == PlayOrderMode.SHUFFLE) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        IconButton(onClick = { onLoopModeChanged(snapshot.loopMode.next()) }) {
            Icon(
                if (snapshot.loopMode == LoopMode.SONG) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                contentDescription = snapshot.loopMode.label,
                tint = if (snapshot.loopMode == LoopMode.OFF) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun QueueList(
    snapshot: PlaybackSnapshot,
    onPlayQueueIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
            Text(
                "Queue",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(formatTrackCount(snapshot.queue.size), style = MaterialTheme.typography.bodySmall)
        }
        if (snapshot.queue.isEmpty()) {
            Text("Queue is empty. Start playback from a folder, file, or genre to populate it.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(snapshot.queue.size) { index ->
                val queueItem = snapshot.queue[index]
                val isCurrentItem = index == snapshot.currentIndex
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlayQueueIndex(index) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrentItem) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (isCurrentItem) "▶ ${queueItem.title}" else queueItem.title,
                            fontWeight = if (isCurrentItem) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = queueItem.queueContext(),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PlayScreenPreview() {
    GraviMediaPlayerTheme {
        PlayScreen(
            snapshot = PlaybackSnapshot(),
            expanded = true,
            onCollapse = {},
            onPlayPause = {},
            onNext = {},
            onPrevious = {},
            onSeek = {},
            onPlayQueueIndex = {},
            onPlayOrderModeChanged = {},
            onLoopModeChanged = {},
        )
    }
}