package com.example.gravimediaplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.example.gravimediaplayer.ui.theme.GraviMediaPlayerTheme

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
    var playbackService by remember { mutableStateOf<PlaybackService?>(null) }
    var playbackSnapshot by remember { mutableStateOf(PlaybackSnapshot()) }

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
                            painterResource(it.icon),
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

                    AppDestinations.PLAY -> PlayScreen(
                        snapshot = playbackSnapshot,
                        onPlayPause = { playbackService?.togglePlayPause() },
                        onNext = { playbackService?.playNext() },
                        onPrevious = { playbackService?.playPrevious() },
                        onSeek = { playbackService?.seekTo(it) },
                        onShuffleChanged = { playbackService?.setShuffle(it) },
                        onLoopChanged = { playbackService?.setLoop(it) },
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
    val icon: Int,
) {
    SELECT("Select", R.drawable.ic_home),
    PLAY("Play", R.drawable.ic_favorite),
    SETTINGS("Settings", R.drawable.ic_account_box),
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
            Text(if (entry.isDirectory) "📁" else "♪", style = MaterialTheme.typography.titleLarge)
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
    onShuffleChanged: (Boolean) -> Unit,
    onLoopChanged: (Boolean) -> Unit,
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
            Button(onClick = onPrevious, enabled = item != null) {
                Text("Previous")
            }
            Button(onClick = onPlayPause, enabled = item != null) {
                Text(if (snapshot.isPlaying) "Pause" else "Play")
            }
            Button(onClick = onNext, enabled = item != null) {
                Text("Next")
            }
        }
        SettingSwitchRow("Shuffle", snapshot.shuffleEnabled, onShuffleChanged)
        SettingSwitchRow("Loop song", snapshot.loopEnabled, onLoopChanged)
        if (snapshot.errorMessage != null) {
            Text(snapshot.errorMessage, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun SettingSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
    folderStack: List<String>
): List<AudioItem> {
    val folder = findFolder(context, rootUriString, folderStack) ?: return emptyList()
    return collectAudioItems(folder, folderStack.joinToString("/"))
}

fun collectAudioItems(folder: DocumentFile, folderPath: String): List<AudioItem> {
    return folder.listFiles()
        .sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy {
            it.name.orEmpty().lowercase()
        })
        .flatMap { document ->
            val name = document.name.orEmpty()
            val childFolderPath =
                listOf(folderPath, name).filter { it.isNotBlank() }.joinToString("/")
            when {
                document.isDirectory -> collectAudioItems(document, childFolderPath)
                document.isFile && isAudioFile(name) -> listOf(
                    AudioItem(
                        document.uri.toString(),
                        name,
                        folderPath
                    )
                )

                else -> emptyList()
            }
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

private const val PREFERENCES_NAME = "gravi_media_player"
private const val KEY_ROOT_URI = "root_uri"

private val AUDIO_EXTENSIONS =
    setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "mid", "midi")

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
            onShuffleChanged = {},
            onLoopChanged = {},
        )
    }
}