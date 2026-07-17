package com.example.gravimediaplayer.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.gravimediaplayer.AudioItem
import com.example.gravimediaplayer.LoopMode
import com.example.gravimediaplayer.PlayOrderMode
import com.example.gravimediaplayer.PlaybackSnapshot
import com.example.gravimediaplayer.formatTime
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
                Text(item.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            .padding(start = 12.dp, top = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = expanded, onClick = onCollapse),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCollapse, enabled = expanded) {
                Icon(Icons.Filled.ExpandLess, contentDescription = "Collapse player")
            }
            Text(
                "Now playing",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        ArtworkCard(item?.artworkUriString)
        Text(
            text = item?.displayTitle ?: "Nothing playing",
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
            Text(formatTime(snapshot.positionMs), style = MaterialTheme.typography.bodySmall)
            Text(
                text = snapshot.audioInfoText.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(formatTime(snapshot.durationMs), style = MaterialTheme.typography.bodySmall)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, enabled = item != null) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
            }
            IconButton(
                onClick = onPlayPause,
                enabled = item != null,
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (snapshot.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(44.dp),
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
        NowPlayingTabs(
            snapshot = snapshot,
            onPlayQueueIndex = onPlayQueueIndex,
            modifier = Modifier.weight(1f),
        )
    }
}

private enum class NowPlayingTab(
    val title: String,
) {
    QUEUE("Queue"),
    LYRICS("Lyrics"),
}

@Composable
private fun NowPlayingTabs(
    snapshot: PlaybackSnapshot,
    onPlayQueueIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(NowPlayingTab.QUEUE) }

    Column(modifier = modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = NowPlayingTab.entries.indexOf(selectedTab)) {
            NowPlayingTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = {
                        Text(
                            if (tab == NowPlayingTab.QUEUE) {
                                "${tab.title} (${snapshot.queue.size} tracks)"
                            } else {
                                tab.title
                            }
                        )
                    },
                )
            }
        }
        when (selectedTab) {
            NowPlayingTab.QUEUE -> QueueList(
                snapshot = snapshot,
                onPlayQueueIndex = onPlayQueueIndex,
                modifier = Modifier.weight(1f),
            )

            NowPlayingTab.LYRICS -> LyricsView(
                audioItem = snapshot.currentItem,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ArtworkCard(artworkUriString: String?) {
    val context = LocalContext.current
    val artworkBitmap = remember(artworkUriString) {
        artworkUriString?.let { uriString ->
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uriString))?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
            }.getOrNull()
        }
    }

    Card(
        modifier = Modifier.size(180.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (artworkBitmap != null) {
                Image(
                    bitmap = artworkBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("♪", style = MaterialTheme.typography.displayLarge)
            }
        }
    }
}

@Composable
private fun PlaybackModeRow(
    snapshot: PlaybackSnapshot,
    onPlayOrderModeChanged: (PlayOrderMode) -> Unit,
    onLoopModeChanged: (LoopMode) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayOrderModeSelector(snapshot.playOrderMode, onPlayOrderModeChanged)
        LoopModeSelector(snapshot.loopMode, onLoopModeChanged)
    }
}

@Composable
private fun PlayOrderModeSelector(
    playOrderMode: PlayOrderMode,
    onPlayOrderModeChanged: (PlayOrderMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Shuffle, contentDescription = null)
            Text(playOrderMode.label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PlayOrderMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = {
                        expanded = false
                        onPlayOrderModeChanged(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun LoopModeSelector(
    loopMode: LoopMode,
    onLoopModeChanged: (LoopMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(
                if (loopMode == LoopMode.SONG) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                contentDescription = null,
            )
            Text(loopMode.label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LoopMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = {
                        expanded = false
                        onLoopModeChanged(mode)
                    },
                )
            }
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (snapshot.queue.isEmpty()) {
            Text("Queue is empty. Start playback from a folder, file, or genre to populate it.")
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
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
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = if (isCurrentItem) "▶ ${queueItem.displayTitle}" else queueItem.displayTitle,
                            fontWeight = if (isCurrentItem) FontWeight.Bold else FontWeight.Normal,
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

@Composable
private fun LyricsView(
    audioItem: AudioItem?,
    modifier: Modifier = Modifier,
) {
    val lyrics = audioItem?.lyrics?.takeIf { it.isNotBlank() }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(
                text = lyrics ?: if (audioItem == null) {
                    "No song is playing."
                } else {
                    "No lyrics found for this song."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
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