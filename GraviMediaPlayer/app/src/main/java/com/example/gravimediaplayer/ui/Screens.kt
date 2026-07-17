package com.example.gravimediaplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.gravimediaplayer.BrowserEntry
import com.example.gravimediaplayer.GraviPickerSettings
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
    isFolderActionRunning: Boolean,
    onChooseFolder: () -> Unit,
    onOpenFolder: (BrowserEntry) -> Unit,
    onBack: () -> Unit,
    onPlayFolder: () -> Unit,
    onShuffleFolder: () -> Unit,
    onGraviShuffleFolder: () -> Unit,
    onExportFolder: () -> Unit,
    onPlayFile: (BrowserEntry) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Folders",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        if (rootUriString == null) {
            Text("Choose your music folder to start browsing local audio files.")
            Button(onClick = onChooseFolder) { Text("Choose music folder") }
            return@Column
        }

        Text(
            text = formatTrackCount(currentTrackCount),
            style = MaterialTheme.typography.bodySmall,
        )
        val hasPlayableFolderContent = entries.isNotEmpty()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                enabled = folderStack.isNotEmpty(),
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
            }
            CompactFolderButton(
                "Ordered",
                hasPlayableFolderContent && !isFolderActionRunning,
                onPlayFolder
            )
            CompactFolderButton(
                "Shuffled",
                hasPlayableFolderContent && !isFolderActionRunning,
                onShuffleFolder
            )
            CompactFolderButton(
                "Gravi",
                hasPlayableFolderContent && !isFolderActionRunning,
                onGraviShuffleFolder
            )
            CompactFolderButton(
                "Export",
                hasPlayableFolderContent && !isFolderActionRunning,
                onExportFolder
            )
        }
        if (isFolderActionRunning) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text("Preparing folder…")
            }
        }
        if (entries.isEmpty()) {
            Text("No folders or supported audio files found here. Supported extensions: ${LibraryRepository.audioExtensions.joinToString()}.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
private fun CompactFolderButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .height(36.dp)
            .defaultMinSize(minWidth = 1.dp, minHeight = 1.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun GenresScreen(
    rootUriString: String?,
    tagGroups: List<TagGroup>,
    isLibraryScanning: Boolean,
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
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        if (rootUriString == null) {
            Text("Choose your music folder before using genre playlists.")
            Button(onClick = onChooseFolder) { Text("Choose music folder") }
            return@Column
        }

        if (isLibraryScanning) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text("Scanning music library…")
            }
            Text("Reading genre metadata. You can keep using the other tabs while this finishes.")
            if (tagGroups.isEmpty()) return@Column
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
fun SettingsScreen(
    rootUriString: String?,
    genreSeparator: String,
    graviPickerSettings: GraviPickerSettings,
    onChooseFolder: () -> Unit,
    onGenreSeparatorChanged: (String) -> Unit,
    onGraviPickerSettingsChanged: (GraviPickerSettings) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(if (rootUriString == null) "No music folder selected." else "Music folder selected and remembered.")
        Button(onClick = onChooseFolder) {
            Text(if (rootUriString == null) "Choose music folder" else "Change music folder")
        }
        OutlinedTextField(
            value = genreSeparator,
            onValueChange = onGenreSeparatorChanged,
            label = { Text("Genre separator") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Gravi shuffle",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        IntegerSettingField(
            label = "Queue entries",
            value = graviPickerSettings.queueEntries,
            onValueChanged = {
                onGraviPickerSettingsChanged(
                    graviPickerSettings.copy(queueEntries = it).sanitized()
                )
            },
        )
        IntegerSettingField(
            label = "Depth",
            value = graviPickerSettings.depth,
            onValueChanged = {
                onGraviPickerSettingsChanged(graviPickerSettings.copy(depth = it).sanitized())
            },
        )
        SwitchSettingRow(
            label = "Parent odds",
            checked = graviPickerSettings.parentOdds,
            onCheckedChanged = {
                onGraviPickerSettingsChanged(graviPickerSettings.copy(parentOdds = it).sanitized())
            },
        )
        SwitchSettingRow(
            label = "Child odds",
            checked = graviPickerSettings.childOdds,
            onCheckedChanged = {
                onGraviPickerSettingsChanged(graviPickerSettings.copy(childOdds = it).sanitized())
            },
        )
        IntegerSettingField(
            label = "Even odds min file count",
            value = graviPickerSettings.evenOddsMinFileCount,
            onValueChanged = {
                onGraviPickerSettingsChanged(
                    graviPickerSettings.copy(evenOddsMinFileCount = it).sanitized()
                )
            },
        )
        DecimalSettingField(
            label = "Less likely divisor",
            value = graviPickerSettings.lessLikelyDivisor,
            onValueChanged = {
                onGraviPickerSettingsChanged(
                    graviPickerSettings.copy(lessLikelyDivisor = it).sanitized()
                )
            },
        )
    }
}

@Composable
private fun IntegerSettingField(label: String, value: Int, onValueChanged: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { newValue ->
            newValue.toIntOrNull()?.let(onValueChanged)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DecimalSettingField(label: String, value: Float, onValueChanged: (Float) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { newValue ->
            newValue.toFloatOrNull()?.let(onValueChanged)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchSettingRow(
    label: String,
    checked: Boolean,
    onCheckedChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChanged)
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
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.MusicNote,
                contentDescription = null
            )
            Text(
                entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (entry.trackCount != null) {
                Text(
                    formatTrackCount(entry.trackCount),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
