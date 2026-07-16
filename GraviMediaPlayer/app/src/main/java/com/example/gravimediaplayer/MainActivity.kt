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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.SELECT) }
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
        tagGroups = if (rootUri == null) {
            emptyList()
        } else {
            buildTagGroups(loadRecursiveAudioItems(context, rootUri, emptyList(), readTags = true))
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
            Box(modifier = Modifier.padding(innerPadding)) {
                when (currentDestination) {
                    AppDestinations.SELECT -> SelectScreen(
                        rootUriString = rootUriString,
                        folderStack = folderStack,
                        entries = browserEntries,
                        onChooseFolder = { folderPicker.launch(null) },
                        onOpenFolder = { folderStack = folderStack + it.name },
                        onBack = { folderStack = folderStack.dropLast(1) },
                        onPlayFolder = {
                            val rootUri = rootUriString ?: return@SelectScreen
                            val queue = loadRecursiveAudioItems(context, rootUri, folderStack)
                            PlaybackService.start(context)
                            playbackService?.playQueue(queue, 0)
                            currentDestination = AppDestinations.PLAY
                        },
                        onPlayFile = { entry ->
                            val selectedItem = entry.audioItem ?: return@SelectScreen
                            val queue = browserEntries.mapNotNull { it.audioItem }
                            val startIndex =
                                queue.indexOfFirst { it.uriString == selectedItem.uriString }
                                    .coerceAtLeast(0)
                            PlaybackService.start(context)
                            playbackService?.playQueue(queue, startIndex)
                            currentDestination = AppDestinations.PLAY
                        },
                    )

                    AppDestinations.TAGS -> TagsScreen(
                        rootUriString = rootUriString,
                        tagGroups = tagGroups,
                        onChooseFolder = { folderPicker.launch(null) },
                        onPlayTag = { tagGroup ->
                            val startIndex = if (savedPlayOrderMode == PlayOrderMode.SHUFFLE) {
                                Random.nextInt(tagGroup.items.size)
                            } else {
                                0
                            }
                            PlaybackService.start(context)
                            playbackService?.playQueue(tagGroup.items, startIndex)
                            currentDestination = AppDestinations.PLAY
                        },
                    )

                    AppDestinations.PLAY -> PlayScreen(
                        snapshot = playbackSnapshot,
                        onPlayPause = { playbackService?.togglePlayPause() },
                        onNext = { playbackService?.playNext() },
                        onPrevious = { playbackService?.playPrevious() },
                        onSeek = { playbackService?.seekTo(it) },
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

                    AppDestinations.SETTINGS -> SettingsScreen(
                        rootUriString = rootUriString,
                        onChooseFolder = { folderPicker.launch(null) },
                    )
                }
            }
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    SELECT("Select", Icons.Filled.Folder),
    TAGS("Tags", Icons.Filled.LocalOffer),
    PLAY("Play", Icons.Filled.PlayCircle),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@Composable
fun SelectScreen(
    rootUriString: String?,
    folderStack: List<String>,
    entries: List<BrowserEntry>,
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
        Text("Music", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (rootUriString == null) {
            Text("Choose your music folder to start browsing local audio files.")
            Button(onClick = onChooseFolder) {
                Text("Choose music folder")
            }
            return@Column
        }

        Text(
            text = if (folderStack.isEmpty()) "Selected folder" else folderStack.joinToString(" / "),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack, enabled = folderStack.isNotEmpty()) {
                Text("Back")
            }
            Button(onClick = onPlayFolder) {
                Text("Play this folder")
            }
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
fun TagsScreen(
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
        Text("Tags", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (rootUriString == null) {
            Text("Choose your music folder before using metadata playlists.")
            Button(onClick = onChooseFolder) {
                Text("Choose music folder")
            }
            return@Column
        }

        if (tagGroups.isEmpty()) {
            Text("No Genre tags found. Tags are read from audio metadata each time the app starts.")
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
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PlayScreen(
    snapshot: PlaybackSnapshot,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Int) -> Unit,
    onPlayOrderModeChanged: (PlayOrderMode) -> Unit,
    onLoopModeChanged: (LoopMode) -> Unit,
) {
    val item = snapshot.currentItem
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Now playing",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
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
            text = item?.folderPath ?: "Select a file or folder to begin.",
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
        ModeSelectorRow(
            label = "Order",
            selected = snapshot.playOrderMode,
            values = PlayOrderMode.entries,
            onSelected = onPlayOrderModeChanged,
        )
        ModeSelectorRow(
            label = "Loop",
            selected = snapshot.loopMode,
            values = LoopMode.entries,
            onSelected = onLoopModeChanged,
        )
        if (snapshot.errorMessage != null) {
            Text(snapshot.errorMessage, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun <T> ModeSelectorRow(
    label: String,
    selected: T,
    values: Iterable<T>,
    onSelected: (T) -> Unit
) where T : Enum<T>, T : ModeLabel {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { value ->
                Button(onClick = { onSelected(value) }, enabled = value != selected) {
                    Text(value.label)
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
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            .orEmpty()
            .split('|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    } catch (_: RuntimeException) {
        emptyList()
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

private const val PREFERENCES_NAME = "gravi_media_player"
private const val KEY_ROOT_URI = "root_uri"
private const val KEY_PLAY_ORDER_MODE = "play_order_mode"
private const val KEY_LOOP_MODE = "loop_mode"

private val AUDIO_EXTENSIONS =
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
            onPlayPause = {},
            onNext = {},
            onPrevious = {},
            onSeek = {},
            onPlayOrderModeChanged = {},
            onLoopModeChanged = {},
        )
    }
}