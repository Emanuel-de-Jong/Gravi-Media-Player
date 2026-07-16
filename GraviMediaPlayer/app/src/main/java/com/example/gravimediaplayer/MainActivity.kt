package com.example.gravimediaplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.example.gravimediaplayer.ui.theme.GraviMediaPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GraviMediaPlayerTheme {
                GraviMediaPlayerApp()
            }
        }
    }
}

@PreviewScreenSizes
@Composable
fun GraviMediaPlayerApp() {
    val context = LocalContext.current
    val preferences =
        remember { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.FOLDERS) }
    var isPlayerExpanded by rememberSaveable { mutableStateOf(false) }
    var rootUriString by rememberSaveable {
        mutableStateOf(
            preferences.getString(
                KEY_ROOT_URI,
                null
            )
        )
    }
    var folderStack by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var browserEntries by remember { mutableStateOf(emptyList<BrowserEntry>()) }
    var tagGroups by remember { mutableStateOf(emptyList<TagGroup>()) }
    var isLibraryScanning by remember { mutableStateOf(rootUriString != null) }
    var pendingPlaybackRequest by remember { mutableStateOf<PendingPlaybackRequest?>(null) }
    var playbackService by remember { mutableStateOf<PlaybackService?>(null) }
    var playbackSnapshot by remember { mutableStateOf(PlaybackSnapshot()) }
    var savedPlayOrderMode by rememberSaveable {
        mutableStateOf(loadPlayOrderMode(preferences))
    }
    var savedLoopMode by rememberSaveable {
        mutableStateOf(loadLoopMode(preferences))
    }

    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
                val newUriString = uri.toString()
                preferences.edit().putString(KEY_ROOT_URI, newUriString).apply()
                rootUriString = newUriString
                folderStack = emptyList()
            }
        }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val boundService = (service as PlaybackService.PlaybackBinder).getService()
                playbackService = boundService
                boundService.setPlayOrderMode(savedPlayOrderMode)
                boundService.setLoopMode(savedLoopMode)
                playbackSnapshot = boundService.getSnapshot()
                boundService.setListener { playbackSnapshot = it }
                pendingPlaybackRequest?.let {
                    boundService.playQueue(it.queue, it.startIndex, it.queueTitle)
                    pendingPlaybackRequest = null
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                playbackService?.setListener(null)
                playbackService = null
            }
        }
        context.bindService(
            Intent(context, PlaybackService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        onDispose {
            playbackService?.setListener(null)
            context.unbindService(connection)
        }
    }

    LaunchedEffect(rootUriString, folderStack) {
        val rootUri = rootUriString
        browserEntries = if (rootUri == null) {
            emptyList()
        } else {
            loadBrowserEntries(context, rootUri, folderStack)
        }
    }

    LaunchedEffect(rootUriString) {
        val rootUri = rootUriString
        if (rootUri == null) {
            isLibraryScanning = false
            tagGroups = emptyList()
        } else {
            isLibraryScanning = true
            tagGroups = withContext(Dispatchers.IO) {
                buildTagGroups(
                    loadRecursiveAudioItems(
                        context,
                        rootUri,
                        emptyList(),
                        readTags = true
                    )
                )
            }
            isLibraryScanning = false
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (isLibraryScanning) {
        LibraryLoadingScreen()
        return
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            it.icon,
                            contentDescription = it.label
                        )
                    },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                if (isPlayerExpanded) {
                    PlayScreen(
                        snapshot = playbackSnapshot,
                        expanded = true,
                        onCollapse = { isPlayerExpanded = false },
                        onPlayPause = { playbackService?.togglePlayPause() },
                        onNext = { playbackService?.playNext() },
                        onPrevious = { playbackService?.playPrevious() },
                        onSeek = { playbackService?.seekTo(it) },
                        onPlayQueueIndex = { playbackService?.playQueueIndex(it) },
                        onPlayOrderModeChanged = {
                            savedPlayOrderMode = it
                            preferences.edit().putString(KEY_PLAY_ORDER_MODE, it.name).apply()
                            playbackService?.setPlayOrderMode(it)
                        },
                        onLoopModeChanged = {
                            savedLoopMode = it
                            preferences.edit().putString(KEY_LOOP_MODE, it.name).apply()
                            playbackService?.setLoopMode(it)
                        },
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            when (currentDestination) {
                                AppDestinations.FOLDERS -> FoldersScreen(
                                    rootUriString = rootUriString,
                                    folderStack = folderStack,
                                    entries = browserEntries,
                                    currentTrackCount = browserEntries.sumOf {
                                        it.trackCount ?: if (it.audioItem != null) 1 else 0
                                    },
                                    onChooseFolder = { folderPicker.launch(null) },
                                    onOpenFolder = { folderStack = folderStack + it.name },
                                    onBack = { folderStack = folderStack.dropLast(1) },
                                    onPlayFolder = {
                                        val rootUri = rootUriString ?: return@FoldersScreen
                                        val queue =
                                            loadRecursiveAudioItems(context, rootUri, folderStack)
                                        val queueTitle = if (folderStack.isEmpty()) {
                                            "Folder: Root music folder"
                                        } else {
                                            "Folder: ${folderStack.joinToString(" / ")}"
                                        }
                                        PlaybackService.start(context)
                                        if (playbackService == null) {
                                            pendingPlaybackRequest =
                                                PendingPlaybackRequest(queue, 0, queueTitle)
                                        } else {
                                            playbackService?.playQueue(queue, 0, queueTitle)
                                        }
                                    },
                                    onPlayFile = { entry ->
                                        val selectedItem = entry.audioItem ?: return@FoldersScreen
                                        val queue = browserEntries.mapNotNull { it.audioItem }
                                        val startIndex =
                                            queue.indexOfFirst { it.uriString == selectedItem.uriString }
                                                .coerceAtLeast(0)
                                        val queueTitle = if (folderStack.isEmpty()) {
                                            "Folder: Root music folder"
                                        } else {
                                            "Folder: ${folderStack.joinToString(" / ")}"
                                        }
                                        PlaybackService.start(context)
                                        if (playbackService == null) {
                                            pendingPlaybackRequest = PendingPlaybackRequest(
                                                queue,
                                                startIndex,
                                                queueTitle
                                            )
                                        } else {
                                            playbackService?.playQueue(
                                                queue,
                                                startIndex,
                                                queueTitle
                                            )
                                        }
                                    },
                                )

                                AppDestinations.GENRES -> GenresScreen(
                                    rootUriString = rootUriString,
                                    tagGroups = tagGroups,
                                    onChooseFolder = { folderPicker.launch(null) },
                                    onPlayTag = { tagGroup ->
                                        val startIndex =
                                            if (savedPlayOrderMode == PlayOrderMode.SHUFFLE) {
                                                Random.nextInt(tagGroup.items.size)
                                            } else {
                                                0
                                            }
                                        val queueTitle = "Genre: ${tagGroup.name}"
                                        PlaybackService.start(context)
                                        if (playbackService == null) {
                                            pendingPlaybackRequest = PendingPlaybackRequest(
                                                tagGroup.items,
                                                startIndex,
                                                queueTitle
                                            )
                                        } else {
                                            playbackService?.playQueue(
                                                tagGroup.items,
                                                startIndex,
                                                queueTitle
                                            )
                                        }
                                    },
                                )

                                AppDestinations.SETTINGS -> SettingsScreen(
                                    rootUriString = rootUriString,
                                    onChooseFolder = { folderPicker.launch(null) },
                                )
                            }
                        }
                        MiniPlayer(
                            snapshot = playbackSnapshot,
                            onExpand = { isPlayerExpanded = true },
                            onPlayPause = { playbackService?.togglePlayPause() },
                            onNext = { playbackService?.playNext() },
                        )
                    }
                }
            }
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    FOLDERS("Folders", Icons.Filled.Folder),
    GENRES("Genres", Icons.Filled.LocalOffer),
    SETTINGS("Settings", Icons.Filled.Settings),
}

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
            Button(onClick = onChooseFolder) {
                Text("Choose music folder")
            }
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
            Button(onClick = onPlayFolder, enabled = currentTrackCount > 0) {
                Text("Play this folder")
            }
        }
        if (entries.isEmpty()) {
            Text("No folders or supported audio files found here. Supported extensions: ${AUDIO_EXTENSIONS.joinToString()}.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.uriString }) { entry ->
                BrowserEntryRow(
                    entry = entry,
                    onClick = {
                        if (entry.isDirectory) {
                            onOpenFolder(entry)
                        } else {
                            onPlayFile(entry)
                        }
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
            Button(onClick = onChooseFolder) {
                Text("Choose music folder")
            }
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
fun BrowserEntryRow(entry: BrowserEntry, onClick: () -> Unit) {
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
                contentDescription = null,
            )
            Column {
                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.trackCount != null) {
                    Text(
                        formatTrackCount(entry.trackCount),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

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
                fontWeight = FontWeight.Bold,
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
fun PlaybackModeRow(
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
fun QueueList(
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

fun loadBrowserEntries(
    context: Context,
    rootUriString: String,
    folderStack: List<String>
): List<BrowserEntry> {
    val folder = findFolder(context, rootUriString, folderStack) ?: return emptyList()
    val folderPath = folderStack.joinToString("/")
    return folder.listFiles()
        .filter { it.isDirectory || isAudioFile(it.name.orEmpty()) }
        .sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy {
            it.name.orEmpty().lowercase()
        })
        .mapNotNull { document ->
            val name = document.name ?: return@mapNotNull null
            BrowserEntry(
                name = name,
                uriString = document.uri.toString(),
                isDirectory = document.isDirectory,
                audioItem = if (document.isFile) AudioItem(
                    document.uri.toString(),
                    name,
                    folderPath
                ) else null,
                trackCount = if (document.isDirectory) countAudioFiles(document) else null,
            )
        }
}

fun loadRecursiveAudioItems(
    context: Context,
    rootUriString: String,
    folderStack: List<String>,
    readTags: Boolean = false,
): List<AudioItem> {
    val folder = findFolder(context, rootUriString, folderStack) ?: return emptyList()
    return collectAudioItems(context, folder, folderStack.joinToString("/"), readTags)
}

fun collectAudioItems(
    context: Context,
    folder: DocumentFile,
    folderPath: String,
    readTags: Boolean,
): List<AudioItem> {
    return folder.listFiles()
        .sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy {
            it.name.orEmpty().lowercase()
        })
        .flatMap { document ->
            val name = document.name.orEmpty()
            val childFolderPath =
                listOf(folderPath, name).filter { it.isNotBlank() }.joinToString("/")
            when {
                document.isDirectory -> collectAudioItems(
                    context,
                    document,
                    childFolderPath,
                    readTags
                )

                document.isFile && isAudioFile(name) -> listOf(
                    AudioItem(
                        document.uri.toString(),
                        name,
                        folderPath,
                        if (readTags) readGenreTags(context, document.uri) else emptyList(),
                    )
                )

                else -> emptyList()
            }
        }
}

fun readGenreTags(context: Context, uri: Uri): List<String> {
    val retriever = MediaMetadataRetriever()
    return try {
        runCatching {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                .orEmpty()
                .split('|')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }.getOrDefault(emptyList())
    } finally {
        retriever.release()
    }
}

fun buildTagGroups(items: List<AudioItem>): List<TagGroup> {
    return items
        .flatMap { item -> item.tags.map { tag -> tag to item } }
        .groupBy({ it.first }, { it.second })
        .map { TagGroup(it.key, it.value.distinctBy { item -> item.uriString }) }
        .sortedBy { it.name.lowercase() }
}

fun AudioItem.queueContext(): String {
    val tagText = tags.takeIf { it.isNotEmpty() }?.joinToString(" | ")
    return listOf(folderPath, tagText)
        .filter { !it.isNullOrBlank() }
        .joinToString(" • ")
        .ifBlank { "No folder or tag info" }
}

fun countAudioFiles(folder: DocumentFile): Int {
    return folder.listFiles().sumOf { document ->
        when {
            document.isDirectory -> countAudioFiles(document)
            document.isFile && isAudioFile(document.name.orEmpty()) -> 1
            else -> 0
        }
    }
}

fun PlayOrderMode.next(): PlayOrderMode {
    return when (this) {
        PlayOrderMode.IN_ORDER -> PlayOrderMode.SHUFFLE
        PlayOrderMode.SHUFFLE -> PlayOrderMode.IN_ORDER
    }
}

fun LoopMode.next(): LoopMode {
    return when (this) {
        LoopMode.OFF -> LoopMode.QUEUE
        LoopMode.QUEUE -> LoopMode.SONG
        LoopMode.SONG -> LoopMode.OFF
    }
}

fun findFolder(context: Context, rootUriString: String, folderStack: List<String>): DocumentFile? {
    var folder = DocumentFile.fromTreeUri(context, Uri.parse(rootUriString)) ?: return null
    folderStack.forEach { folderName ->
        folder = folder.listFiles().firstOrNull { it.isDirectory && it.name == folderName }
            ?: return null
    }
    return folder
}

fun isAudioFile(name: String): Boolean {
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in AUDIO_EXTENSIONS
}

fun formatTime(milliseconds: Int): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

fun formatTrackCount(trackCount: Int): String {
    return if (trackCount == 1) {
        "1 track"
    } else {
        "$trackCount tracks"
    }
}

private const val PREFERENCES_NAME = "gravi_media_player"
private const val KEY_ROOT_URI = "root_uri"
private const val KEY_PLAY_ORDER_MODE = "play_order_mode"
private const val KEY_LOOP_MODE = "loop_mode"

val AUDIO_EXTENSIONS =
    setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "mid", "midi")

fun loadPlayOrderMode(preferences: android.content.SharedPreferences): PlayOrderMode {
    val modeName = preferences.getString(KEY_PLAY_ORDER_MODE, PlayOrderMode.IN_ORDER.name)
    return PlayOrderMode.entries.firstOrNull { it.name == modeName } ?: PlayOrderMode.IN_ORDER
}

fun loadLoopMode(preferences: android.content.SharedPreferences): LoopMode {
    val modeName = preferences.getString(KEY_LOOP_MODE, LoopMode.OFF.name)
    return LoopMode.entries.firstOrNull { it.name == modeName } ?: LoopMode.OFF
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