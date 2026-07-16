package com.example.gravimediaplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.gravimediaplayer.BrowserEntry
import com.example.gravimediaplayer.LibraryRepository
import com.example.gravimediaplayer.TagGroup
import com.example.gravimediaplayer.formatTrackCount

@Composable
fun LibraryLoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text("Scanning music library…", style = MaterialTheme.typography.titleMedium)
            Text("Reading genre metadata. This can take a moment for large libraries.")
        }
    }
}

@Composable
fun FoldersScreen(
    rootUriString: String?,
    folderStack: List<String>,
    entries: List<BrowserEntry>,
    currentTrackCount: Int,
    onChooseFolder: () -> Unit,
    onOpenFolder: (BrowserEntry) -> Unit,
    onBack: () -> Unit,
    onPlayFolder: () -> Unit,
    onPlayFile: (BrowserEntry) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Folders",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        if (rootUriString == null) {
            Text("Choose your music folder to start browsing local audio files.")
            Button(onClick = onChooseFolder) { Text("Choose music folder") }
            return@Column
        }

        Text(
            text = if (folderStack.isEmpty()) "Root music folder" else folderStack.joinToString(" / "),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${formatTrackCount(currentTrackCount)} in this folder tree",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack, enabled = folderStack.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
            }
            Button(
                onClick = onPlayFolder,
                enabled = currentTrackCount > 0
            ) { Text("Play this folder") }
        }
        if (entries.isEmpty()) {
            Text("No folders or supported audio files found here. Supported extensions: ${LibraryRepository.audioExtensions.joinToString()}.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.uriString }) { entry ->
                BrowserEntryRow(
                    entry = entry,
                    onClick = {
                        if (entry.isDirectory) onOpenFolder(entry) else onPlayFile(entry)
                    },
                )
            }
        }
    }
}

@Composable
fun GenresScreen(
    rootUriString: String?,
    tagGroups: List<TagGroup>,
    onChooseFolder: () -> Unit,
    onPlayTag: (TagGroup) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Genres",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        if (rootUriString == null) {
            Text("Choose your music folder before using genre playlists.")
            Button(onClick = onChooseFolder) { Text("Choose music folder") }
            return@Column
        }

        if (tagGroups.isEmpty()) {
            Text("No genre metadata was found. Genre lists are rebuilt from audio metadata every app start so file changes are picked up after restarting.")
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tagGroups, key = { it.name }) { tagGroup ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlayTag(tagGroup) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.LocalOffer, contentDescription = null)
                        Column {
                            Text(tagGroup.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${tagGroup.items.size} tracks",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(rootUriString: String?, onChooseFolder: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(if (rootUriString == null) "No music folder selected." else "Music folder selected and remembered.")
        Button(onClick = onChooseFolder) {
            Text(if (rootUriString == null) "Choose music folder" else "Change music folder")
        }
        Text("Playback uses a foreground media notification, so music can continue while the screen is locked or another app is open.")
    }
}

@Composable
private fun BrowserEntryRow(entry: BrowserEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.MusicNote,
                contentDescription = null
            )
            Column {
                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.trackCount != null) {
                    Text(
                        formatTrackCount(entry.trackCount),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}