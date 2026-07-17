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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

@PreviewScreenSizes
@Composable
fun GraviMediaPlayerApp() {
    val context = LocalContext.current
    val libraryRepository = remember(context) { LibraryRepository(context) }
    val graviQueuePicker = remember { GraviQueuePicker() }
    val preferences = remember(context) { PlayerPreferences(context) }
    val coroutineScope = rememberCoroutineScope()
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.FOLDERS) }
    var isPlayerExpanded by rememberSaveable { mutableStateOf(false) }
    var rootUriString by rememberSaveable { mutableStateOf(preferences.rootUriString) }
    var folderStack by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var folderSearchQuery by rememberSaveable { mutableStateOf("") }
    var genreSearchQuery by rememberSaveable { mutableStateOf("") }
    var browserEntries by remember { mutableStateOf(emptyList<BrowserEntry>()) }
    var tagGroups by remember { mutableStateOf(emptyList<TagGroup>()) }
    var isGeneratingCache by remember { mutableStateOf(preferences.rootUriString != null) }
    var libraryCacheVersion by remember { mutableStateOf(0) }
    var cacheGenerationRequest by remember { mutableStateOf(0) }
    var pendingPlaybackRequest by remember { mutableStateOf<PendingPlaybackRequest?>(null) }
    var playbackService by remember { mutableStateOf<PlaybackService?>(null) }
    var playbackSnapshot by remember { mutableStateOf(PlaybackSnapshot()) }
    var savedPlayOrderMode by rememberSaveable { mutableStateOf(preferences.playOrderMode) }
    var savedLoopMode by rememberSaveable { mutableStateOf(preferences.loopMode) }
    var genreSeparator by rememberSaveable { mutableStateOf(preferences.genreSeparator) }
    var appliedGenreSeparator by rememberSaveable { mutableStateOf(preferences.genreSeparator) }
    var showBrowserThumbnails by rememberSaveable { mutableStateOf(preferences.showBrowserThumbnails) }
    var graviPickerSettings by remember { mutableStateOf(preferences.graviPickerSettings) }
    var pendingPlaylistExport by remember { mutableStateOf<List<AudioItem>?>(null) }
    var isFolderActionRunning by remember { mutableStateOf(false) }
    var mediaLibraryPermissionVersion by remember { mutableStateOf(0) }

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
                folderSearchQuery = ""
                genreSearchQuery = ""
                browserEntries = emptyList()
                tagGroups = emptyList()
                libraryRepository.clearSessionCache()
                cacheGenerationRequest++
            }
        }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val mediaLibraryPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            libraryRepository.clearSessionCache()
            mediaLibraryPermissionVersion++
        }
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

    LaunchedEffect(
        rootUriString,
        mediaLibraryPermissionVersion,
        appliedGenreSeparator,
        cacheGenerationRequest
    ) {
        val rootUri = rootUriString
        if (rootUri == null) {
            isGeneratingCache = false
            browserEntries = emptyList()
            tagGroups = emptyList()
        } else {
            isGeneratingCache = true
            tagGroups = withContext(Dispatchers.IO) {
                if (!libraryRepository.hasCompleteCache(rootUri, appliedGenreSeparator)) {
                    libraryRepository.generateAllCaches(rootUri, appliedGenreSeparator)
                }
                libraryRepository.buildTagGroups(
                    libraryRepository.loadCachedGenreAudioItems(rootUri, appliedGenreSeparator)
                )
            }
            libraryCacheVersion++
            isGeneratingCache = false
        }
    }

    LaunchedEffect(
        rootUriString,
        folderStack,
        folderSearchQuery,
        libraryCacheVersion,
        isGeneratingCache
    ) {
        val rootUri = rootUriString
        browserEntries =
            if (rootUri == null || isGeneratingCache) emptyList() else withContext(Dispatchers.IO) {
                if (folderSearchQuery.isBlank()) {
                    libraryRepository.loadBrowserEntries(rootUri, folderStack)
                } else {
                    libraryRepository.searchBrowserEntries(rootUri, folderSearchQuery)
                }
            }
    }

    LaunchedEffect(rootUriString) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        rootUriString ?: return@LaunchedEffect
        val mediaLibraryPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (context.checkSelfPermission(mediaLibraryPermission) != PackageManager.PERMISSION_GRANTED) {
            mediaLibraryPermissionLauncher.launch(mediaLibraryPermission)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (!isGeneratingCache) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    NavigationBar(
                        modifier = Modifier.fillMaxSize(),
                        windowInsets = WindowInsets(0.dp),
                    ) {
                        AppDestinations.entries.forEach { destination ->
                            NavigationBarItem(
                                icon = {
                                    Icon(
                                        destination.icon,
                                        contentDescription = destination.label
                                    )
                                },
                                selected = destination == currentDestination,
                                onClick = {
                                    isPlayerExpanded = false
                                    currentDestination = destination
                                },
                                alwaysShowLabel = false,
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (isGeneratingCache) {
                LibraryLoadingScreen()
            } else if (isPlayerExpanded) {
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
                        val filteredTagGroups = tagGroups.filter {
                            genreSearchQuery.isBlank() || it.name.contains(
                                genreSearchQuery,
                                ignoreCase = true
                            )
                        }
                        when (currentDestination) {
                            AppDestinations.FOLDERS -> FoldersScreen(
                                rootUriString = rootUriString,
                                folderStack = folderStack,
                                entries = browserEntries,
                                currentTrackCount = browserEntries.sumOf {
                                    it.trackCount ?: if (it.audioItem != null) 1 else 0
                                },
                                searchQuery = folderSearchQuery,
                                showThumbnails = showBrowserThumbnails,
                                isFolderActionRunning = isFolderActionRunning,
                                onChooseFolder = { folderPicker.launch(null) },
                                onSearchQueryChanged = { folderSearchQuery = it },
                                onOpenFolder = {
                                    folderStack =
                                        it.uriString.split('/').filter { part -> part.isNotBlank() }
                                    folderSearchQuery = ""
                                },
                                onBack = { folderStack = folderStack.dropLast(1) },
                                onPlayFolder = {
                                    val rootUri = rootUriString ?: return@FoldersScreen
                                    val selectedFolderStack = folderStack
                                    coroutineScope.launch {
                                        isFolderActionRunning = true
                                        try {
                                            val queue = withContext(Dispatchers.IO) {
                                                libraryRepository.loadRecursiveAudioItems(
                                                    rootUri,
                                                    selectedFolderStack
                                                )
                                            }
                                            val queueTitle =
                                                folderQueueTitle(selectedFolderStack)
                                            pendingPlaybackRequest = requestPlayback(
                                                context,
                                                playbackService,
                                                queue,
                                                0,
                                                queueTitle,
                                                PlayOrderMode.IN_ORDER,
                                            )
                                        } finally {
                                            isFolderActionRunning = false
                                        }
                                    }
                                },
                                onShuffleFolder = {
                                    val rootUri = rootUriString ?: return@FoldersScreen
                                    val selectedFolderStack = folderStack
                                    val selectedGraviPickerSettings = graviPickerSettings
                                    coroutineScope.launch {
                                        isFolderActionRunning = true
                                        try {
                                            val queue = withContext(Dispatchers.IO) {
                                                graviQueuePicker.buildTrueShuffleQueue(
                                                    libraryRepository.loadRecursiveAudioItems(
                                                        rootUri,
                                                        selectedFolderStack
                                                    ),
                                                    selectedGraviPickerSettings.queueEntries,
                                                )
                                            }
                                            val queueTitle =
                                                "Shuffle: ${
                                                    folderDisplayTitle(
                                                        selectedFolderStack
                                                    )
                                                }"
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
                                        } finally {
                                            isFolderActionRunning = false
                                        }
                                    }
                                },
                                onGraviShuffleFolder = {
                                    val rootUri = rootUriString ?: return@FoldersScreen
                                    val selectedFolderStack = folderStack
                                    val selectedGraviPickerSettings = graviPickerSettings
                                    coroutineScope.launch {
                                        isFolderActionRunning = true
                                        try {
                                            val queue = withContext(Dispatchers.IO) {
                                                graviQueuePicker.buildGraviQueue(
                                                    libraryRepository.loadRecursiveAudioItems(
                                                        rootUri,
                                                        selectedFolderStack
                                                    ),
                                                    selectedGraviPickerSettings,
                                                    selectedFolderStack.joinToString("/"),
                                                )
                                            }
                                            val queueTitle =
                                                "Gravi shuffle: ${
                                                    folderDisplayTitle(
                                                        selectedFolderStack
                                                    )
                                                }"
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
                                        } finally {
                                            isFolderActionRunning = false
                                        }
                                    }
                                },
                                onExportFolder = {
                                    val rootUri = rootUriString ?: return@FoldersScreen
                                    val selectedFolderStack = folderStack
                                    coroutineScope.launch {
                                        isFolderActionRunning = true
                                        try {
                                            val exportItems = withContext(Dispatchers.IO) {
                                                libraryRepository.loadRecursiveAudioItems(
                                                    rootUri,
                                                    selectedFolderStack
                                                )
                                            }
                                            pendingPlaylistExport = exportItems
                                            playlistExporter.launch(
                                                playlistFileName(
                                                    selectedFolderStack
                                                )
                                            )
                                        } finally {
                                            isFolderActionRunning = false
                                        }
                                    }
                                },
                                onPlayFile = { entry ->
                                    val selectedItem = entry.audioItem ?: return@FoldersScreen
                                    val selectedRootUri = rootUriString ?: return@FoldersScreen
                                    val selectedFolderStack = selectedItem.folderPath.split('/')
                                        .filter { it.isNotBlank() }
                                    coroutineScope.launch {
                                        isFolderActionRunning = true
                                        try {
                                            val queue = if (folderSearchQuery.isBlank()) {
                                                browserEntries.mapNotNull { it.audioItem }
                                            } else {
                                                withContext(Dispatchers.IO) {
                                                    libraryRepository.loadRecursiveAudioItems(
                                                        selectedRootUri,
                                                        selectedFolderStack,
                                                    )
                                                }
                                            }
                                            val startIndex =
                                                queue.indexOfFirst { it.uriString == selectedItem.uriString }
                                                    .coerceAtLeast(0)
                                            val queueTitle = folderQueueTitle(
                                                if (folderSearchQuery.isBlank()) folderStack else selectedFolderStack
                                            )
                                            pendingPlaybackRequest = requestPlayback(
                                                context,
                                                playbackService,
                                                queue,
                                                startIndex,
                                                queueTitle,
                                                savedPlayOrderMode,
                                            )
                                        } finally {
                                            isFolderActionRunning = false
                                        }
                                    }
                                },
                            )

                            AppDestinations.GENRES -> GenresScreen(
                                rootUriString = rootUriString,
                                tagGroups = filteredTagGroups,
                                searchQuery = genreSearchQuery,
                                onChooseFolder = { folderPicker.launch(null) },
                                onSearchQueryChanged = { genreSearchQuery = it },
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
                                genreSeparator = genreSeparator,
                                showBrowserThumbnails = showBrowserThumbnails,
                                graviPickerSettings = graviPickerSettings,
                                onChooseFolder = { folderPicker.launch(null) },
                                onGenreSeparatorChanged = {
                                    genreSeparator = it
                                },
                                onApplyGenreSeparator = {
                                    genreSeparator = it
                                    appliedGenreSeparator = it.ifBlank { "|" }
                                    preferences.genreSeparator = it
                                    tagGroups = emptyList()
                                    cacheGenerationRequest++
                                },
                                onShowBrowserThumbnailsChanged = {
                                    showBrowserThumbnails = it
                                    preferences.showBrowserThumbnails = it
                                },
                                onGraviPickerSettingsChanged = {
                                    graviPickerSettings = it
                                    preferences.graviPickerSettings = it
                                },
                                onResetSettings = {
                                    preferences.resetSettingsExceptRootUri()
                                    savedPlayOrderMode = preferences.playOrderMode
                                    savedLoopMode = preferences.loopMode
                                    genreSeparator = preferences.genreSeparator
                                    appliedGenreSeparator = preferences.genreSeparator
                                    showBrowserThumbnails = preferences.showBrowserThumbnails
                                    graviPickerSettings = preferences.graviPickerSettings
                                    playbackService?.setPlayOrderMode(savedPlayOrderMode)
                                    playbackService?.setLoopMode(savedLoopMode)
                                    tagGroups = emptyList()
                                    cacheGenerationRequest++
                                },
                                onClearCaches = {
                                    libraryRepository.clearAllCaches(rootUriString)
                                    browserEntries = emptyList()
                                    tagGroups = emptyList()
                                    cacheGenerationRequest++
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