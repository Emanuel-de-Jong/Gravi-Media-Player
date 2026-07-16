package com.example.gravimediaplayer.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.example.gravimediaplayer.AudioItem
import com.example.gravimediaplayer.BrowserEntry
import com.example.gravimediaplayer.GraviQueuePicker
import com.example.gravimediaplayer.LibraryRepository
import com.example.gravimediaplayer.PendingPlaybackRequest
import com.example.gravimediaplayer.PlayOrderMode
import com.example.gravimediaplayer.PlaybackService
import com.example.gravimediaplayer.PlaybackSnapshot
import com.example.gravimediaplayer.PlayerPreferences
import com.example.gravimediaplayer.TagGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

@PreviewScreenSizes
@Composable
fun GraviMediaPlayerApp() {
    val context = LocalContext.current
    val libraryRepository = remember(context) { LibraryRepository(context) }
    val graviQueuePicker = remember { GraviQueuePicker() }
    val preferences = remember(context) { PlayerPreferences(context) }
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.FOLDERS) }
    var isPlayerExpanded by rememberSaveable { mutableStateOf(false) }
    var rootUriString by rememberSaveable { mutableStateOf(preferences.rootUriString) }
    var folderStack by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var browserEntries by remember { mutableStateOf(emptyList<BrowserEntry>()) }
    var tagGroups by remember { mutableStateOf(emptyList<TagGroup>()) }
    var isLibraryScanning by remember { mutableStateOf(rootUriString != null) }
    var pendingPlaybackRequest by remember { mutableStateOf<PendingPlaybackRequest?>(null) }
    var playbackService by remember { mutableStateOf<PlaybackService?>(null) }
    var playbackSnapshot by remember { mutableStateOf(PlaybackSnapshot()) }
    var savedPlayOrderMode by rememberSaveable { mutableStateOf(preferences.playOrderMode) }
    var savedLoopMode by rememberSaveable { mutableStateOf(preferences.loopMode) }
    var graviPickerSettings by remember { mutableStateOf(preferences.graviPickerSettings) }
    var pendingPlaylistExport by remember { mutableStateOf<List<AudioItem>?>(null) }

    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                rootUriString = uri.toString()
                preferences.rootUriString = rootUriString
                folderStack = emptyList()
            }
        }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val playlistExporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
            val exportItems = pendingPlaylistExport
            pendingPlaylistExport = null
            if (uri != null && exportItems != null) {
                writeM3u8Playlist(context, uri, exportItems)
            }
        }

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
                    savedPlayOrderMode = it.playOrderMode
                    preferences.playOrderMode = it.playOrderMode
                    boundService.setPlayOrderMode(it.playOrderMode)
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
        browserEntries = if (rootUri == null) emptyList() else libraryRepository.loadBrowserEntries(
            rootUri,
            folderStack
        )
    }

    LaunchedEffect(rootUriString) {
        val rootUri = rootUriString
        if (rootUri == null) {
            isLibraryScanning = false
            tagGroups = emptyList()
        } else {
            isLibraryScanning = true
            tagGroups = withContext(Dispatchers.IO) {
                libraryRepository.buildTagGroups(
                    libraryRepository.loadRecursiveAudioItems(rootUri, emptyList(), readTags = true)
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
            AppDestinations.entries.forEach { destination ->
                item(
                    icon = { Icon(destination.icon, contentDescription = destination.label) },
                    label = { Text(destination.label) },
                    selected = destination == currentDestination,
                    onClick = { currentDestination = destination },
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
                            preferences.playOrderMode = it
                            playbackService?.setPlayOrderMode(it)
                        },
                        onLoopModeChanged = {
                            savedLoopMode = it
                            preferences.loopMode = it
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
                                        val queue = libraryRepository.loadRecursiveAudioItems(
                                            rootUri,
                                            folderStack
                                        )
                                        val queueTitle = folderQueueTitle(folderStack)
                                        pendingPlaybackRequest = requestPlayback(
                                            context,
                                            playbackService,
                                            queue,
                                            0,
                                            queueTitle,
                                            PlayOrderMode.IN_ORDER,
                                        )
                                    },
                                    onShuffleFolder = {
                                        val rootUri = rootUriString ?: return@FoldersScreen
                                        val queue = graviQueuePicker.buildTrueShuffleQueue(
                                            libraryRepository.loadRecursiveAudioItems(
                                                rootUri,
                                                folderStack
                                            ),
                                            graviPickerSettings.queueEntries,
                                        )
                                        val queueTitle =
                                            "Shuffle: ${folderDisplayTitle(folderStack)}"
                                        pendingPlaybackRequest = requestPlayback(
                                            context,
                                            playbackService,
                                            queue,
                                            0,
                                            queueTitle,
                                            PlayOrderMode.IN_ORDER,
                                            onPlayOrderModeChanged = {
                                                savedPlayOrderMode = it
                                                preferences.playOrderMode = it
                                            },
                                        )
                                    },
                                    onGraviShuffleFolder = {
                                        val rootUri = rootUriString ?: return@FoldersScreen
                                        val queue = graviQueuePicker.buildGraviQueue(
                                            libraryRepository.loadRecursiveAudioItems(
                                                rootUri,
                                                folderStack
                                            ),
                                            graviPickerSettings,
                                            folderStack.joinToString("/"),
                                        )
                                        val queueTitle =
                                            "Gravi shuffle: ${folderDisplayTitle(folderStack)}"
                                        pendingPlaybackRequest = requestPlayback(
                                            context,
                                            playbackService,
                                            queue,
                                            0,
                                            queueTitle,
                                            PlayOrderMode.GRAVI_SHUFFLE,
                                            onPlayOrderModeChanged = {
                                                savedPlayOrderMode = it
                                                preferences.playOrderMode = it
                                            },
                                        )
                                    },
                                    onExportFolder = {
                                        val rootUri = rootUriString ?: return@FoldersScreen
                                        val exportItems = libraryRepository.loadRecursiveAudioItems(
                                            rootUri,
                                            folderStack
                                        )
                                        pendingPlaylistExport = exportItems
                                        playlistExporter.launch(playlistFileName(folderStack))
                                    },
                                    onPlayFile = { entry ->
                                        val selectedItem = entry.audioItem ?: return@FoldersScreen
                                        val queue = browserEntries.mapNotNull { it.audioItem }
                                        val startIndex =
                                            queue.indexOfFirst { it.uriString == selectedItem.uriString }
                                                .coerceAtLeast(0)
                                        val queueTitle = folderQueueTitle(folderStack)
                                        pendingPlaybackRequest = requestPlayback(
                                            context,
                                            playbackService,
                                            queue,
                                            startIndex,
                                            queueTitle,
                                            savedPlayOrderMode,
                                        )
                                    },
                                )

                                AppDestinations.GENRES -> GenresScreen(
                                    rootUriString = rootUriString,
                                    tagGroups = tagGroups,
                                    onChooseFolder = { folderPicker.launch(null) },
                                    onPlayTag = { tagGroup ->
                                        val startIndex =
                                            if (savedPlayOrderMode == PlayOrderMode.SHUFFLE) Random.nextInt(
                                                tagGroup.items.size
                                            ) else 0
                                        val queueTitle = "Genre: ${tagGroup.name}"
                                        pendingPlaybackRequest = requestPlayback(
                                            context,
                                            playbackService,
                                            tagGroup.items,
                                            startIndex,
                                            queueTitle,
                                            savedPlayOrderMode,
                                        )
                                    },
                                )

                                AppDestinations.SETTINGS -> SettingsScreen(
                                    rootUriString = rootUriString,
                                    graviPickerSettings = graviPickerSettings,
                                    onChooseFolder = { folderPicker.launch(null) },
                                    onGraviPickerSettingsChanged = {
                                        graviPickerSettings = it
                                        preferences.graviPickerSettings = it
                                    },
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

private fun folderQueueTitle(folderStack: List<String>): String {
    return if (folderStack.isEmpty()) "Folder: Root music folder" else "Folder: ${
        folderStack.joinToString(
            " / "
        )
    }"
}

private fun folderDisplayTitle(folderStack: List<String>): String {
    return if (folderStack.isEmpty()) "Root music folder" else folderStack.joinToString(" / ")
}

private fun playlistFileName(folderStack: List<String>): String {
    val folderName = if (folderStack.isEmpty()) "Root music folder" else folderStack.last()
    val safeFolderName = folderName
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .ifBlank { "playlist" }
    return "$safeFolderName.m3u8"
}

private fun writeM3u8Playlist(context: Context, uri: Uri, items: List<AudioItem>) {
    val playlistContent = buildM3u8Playlist(items)
    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
        outputStream.write(playlistContent.toByteArray(Charsets.UTF_8))
    }
}

private fun buildM3u8Playlist(items: List<AudioItem>): String {
    return buildString {
        appendLine("#EXTM3U")
        items.forEach { item ->
            appendLine(item.uriString)
        }
    }
}

private fun requestPlayback(
    context: Context,
    playbackService: PlaybackService?,
    queue: List<AudioItem>,
    startIndex: Int,
    queueTitle: String,
    playOrderMode: PlayOrderMode,
    onPlayOrderModeChanged: ((PlayOrderMode) -> Unit)? = null,
): PendingPlaybackRequest? {
    PlaybackService.start(context)
    onPlayOrderModeChanged?.invoke(playOrderMode)
    playbackService?.setPlayOrderMode(playOrderMode)
    playbackService?.playQueue(queue, startIndex, queueTitle)
    return if (playbackService == null) PendingPlaybackRequest(
        queue,
        startIndex,
        queueTitle,
        playOrderMode,
    ) else null
}