package com.example.gravimediaplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

class PlaybackService : Service() {
    private val binder = PlaybackBinder()
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var listener: ((PlaybackSnapshot) -> Unit)? = null
    private var snapshot = PlaybackSnapshot()
    private var shuffledIndexes = emptyList<Int>()
    private var shuffledPosition = -1

    private val progressUpdater = object : Runnable {
        override fun run() {
            player?.let {
                snapshot = snapshot.copy(
                    positionMs = safePosition(it),
                    durationMs = safeDuration(it),
                )
                notifyListener()
            }
            handler.postDelayed(this, 500)
        }
    }

    inner class PlaybackBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        player = buildPlayer()
        player?.addListener(playbackListener)
        mediaSession = MediaSessionCompat(this, "Gravi Media Player").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(mediaSessionCallback)
            isActive = true
        }
        handler.post(progressUpdater)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun buildPlayer(): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_NONE)
            .build()
        val offloadPreferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
            .setIsGaplessSupportRequired(false)
            .setIsSpeedChangeSupportRequired(false)
            .build()
        return ExoPlayer.Builder(this).build().apply {
            trackSelectionParameters = trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(offloadPreferences)
                .build()
            setAudioAttributes(audioAttributes, true)
            skipSilenceEnabled = false
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_NEXT -> playNext()
            ACTION_PREVIOUS -> playPrevious()
            ACTION_STOP -> stopPlayback()
            else -> updateForegroundNotification()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressUpdater)
        player?.release()
        mediaSession?.release()
        super.onDestroy()
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            if (snapshot.isPlaying) return

            togglePlayPause()
        }

        override fun onPause() {
            if (!snapshot.isPlaying) return

            togglePlayPause()
        }

        override fun onSkipToNext() {
            playNext()
        }

        override fun onSkipToPrevious() {
            playPrevious()
        }

        override fun onStop() {
            stopPlayback()
        }

        override fun onSeekTo(position: Long) {
            seekTo(position.toInt())
        }
    }

    fun setListener(newListener: ((PlaybackSnapshot) -> Unit)?) {
        listener = newListener
        notifyListener()
    }

    fun getSnapshot(): PlaybackSnapshot = snapshot

    fun playQueue(queue: List<AudioItem>, startIndex: Int, queueTitle: String) {
        if (queue.isEmpty()) {
            snapshot = snapshot.copy(errorMessage = "No audio files found.")
            notifyListener()
            return
        }

        val safeIndex = startIndex.coerceIn(queue.indices)
        snapshot = snapshot.copy(
            queue = queue,
            queueTitle = queueTitle,
            currentIndex = safeIndex,
            positionMs = 0,
            durationMs = 0,
            errorMessage = null,
        )
        rebuildShuffleOrder(safeIndex)
        playIndex(safeIndex)
    }

    fun togglePlayPause() {
        val currentPlayer = player ?: return
        if (currentPlayer.isPlaying) {
            currentPlayer.pause()
            snapshot = snapshot.copy(isPlaying = false, positionMs = safePosition(currentPlayer))
        } else {
            currentPlayer.play()
            snapshot = snapshot.copy(isPlaying = true, positionMs = safePosition(currentPlayer))
        }
        updateMediaSession()
        updateForegroundNotification()
        notifyListener()
    }

    fun playNext() {
        val nextIndex = getNextIndex() ?: return
        playIndex(nextIndex)
    }

    fun playPrevious() {
        val currentPlayer = player
        if (currentPlayer != null && safePosition(currentPlayer) > 3000) {
            seekTo(0)
            return
        }

        val previousIndex = getPreviousIndex() ?: return
        playIndex(previousIndex)
    }

    fun playQueueIndex(index: Int) {
        if (index !in snapshot.queue.indices) return

        playIndex(index)
    }

    fun seekTo(positionMs: Int) {
        val currentPlayer = player ?: return
        currentPlayer.seekTo(positionMs.coerceIn(0, safeDuration(currentPlayer)).toLong())
        snapshot = snapshot.copy(positionMs = safePosition(currentPlayer))
        updateMediaSession()
        notifyListener()
    }

    fun setPlayOrderMode(mode: PlayOrderMode) {
        snapshot = snapshot.copy(playOrderMode = mode)
        rebuildShuffleOrder(snapshot.currentIndex)
        notifyListener()
    }

    fun setLoopMode(mode: LoopMode) {
        snapshot = snapshot.copy(loopMode = mode)
        notifyListener()
    }

    private fun playIndex(index: Int) {
        val item = snapshot.queue.getOrNull(index) ?: return
        val currentPlayer = player ?: return
        val playbackItem = readPlaybackMetadata(item)
        val updatedQueue = snapshot.queue.toMutableList().apply {
            this[index] = playbackItem
        }
        currentPlayer.stop()
        currentPlayer.clearMediaItems()
        currentPlayer.setMediaItem(MediaItem.fromUri(playbackItem.uri))
        currentPlayer.prepare()
        currentPlayer.play()
        snapshot = snapshot.copy(
            queue = updatedQueue,
            currentIndex = index,
            isPlaying = true,
            positionMs = 0,
            durationMs = playbackItem.durationMs?.toInt() ?: 0,
            audioInfoText = playbackItem.compactAudioInfo(),
            errorMessage = null
        )
        if (snapshot.playOrderMode == PlayOrderMode.SHUFFLE && index in shuffledIndexes) {
            shuffledPosition = shuffledIndexes.indexOf(index)
        }
        mediaSession?.isActive = true
        updateMediaSession()
        updateForegroundNotification()
        notifyListener()
    }

    private fun stopPlayback() {
        player?.stop()
        player?.clearMediaItems()
        mediaSession?.isActive = false
        snapshot = PlaybackSnapshot(
            playOrderMode = snapshot.playOrderMode,
            loopMode = snapshot.loopMode,
        )
        shuffledIndexes = emptyList()
        shuffledPosition = -1
        updateMediaSession()
        notifyListener()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val playbackListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> updatePlaybackState()
                Player.STATE_ENDED -> handlePlaybackEnded()
                Player.STATE_BUFFERING -> Unit
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            snapshot = snapshot.copy(isPlaying = false, errorMessage = "Unable to play this file.")
            updateForegroundNotification()
            notifyListener()
        }
    }

    private fun updatePlaybackState() {
        val currentPlayer = player ?: return
        snapshot = snapshot.copy(
            isPlaying = currentPlayer.isPlaying,
            positionMs = safePosition(currentPlayer),
            durationMs = safeDuration(currentPlayer),
            audioInfoText = snapshot.currentItem?.compactAudioInfo(),
            errorMessage = null,
        )
        updateMediaSession()
        updateForegroundNotification()
        notifyListener()
    }

    private fun handlePlaybackEnded() {
        if (snapshot.loopMode == LoopMode.SONG) {
            playIndex(snapshot.currentIndex)
            return
        }

        val nextIndex = getNextIndex()
        if (nextIndex == null) {
            snapshot = snapshot.copy(isPlaying = false, positionMs = snapshot.durationMs)
            updateMediaSession()
            updateForegroundNotification()
            notifyListener()
        } else {
            playIndex(nextIndex)
        }
    }

    private fun getNextIndex(): Int? {
        if (snapshot.queue.isEmpty()) return null

        if (snapshot.playOrderMode == PlayOrderMode.SHUFFLE) {
            if (shuffledIndexes.isEmpty()) rebuildShuffleOrder(snapshot.currentIndex)
            val nextPosition = shuffledPosition + 1
            if (nextPosition in shuffledIndexes.indices) {
                shuffledPosition = nextPosition
                return shuffledIndexes[shuffledPosition]
            }
            return if (snapshot.loopMode == LoopMode.QUEUE) {
                rebuildShuffleOrder(snapshot.currentIndex)
                shuffledPosition = 0
                shuffledIndexes.firstOrNull()
            } else {
                null
            }
        }

        val nextIndex = snapshot.currentIndex + 1
        return when {
            nextIndex in snapshot.queue.indices -> nextIndex
            snapshot.loopMode == LoopMode.QUEUE -> 0
            else -> null
        }
    }

    private fun getPreviousIndex(): Int? {
        if (snapshot.queue.isEmpty()) return null

        if (snapshot.playOrderMode == PlayOrderMode.SHUFFLE) {
            val previousPosition = shuffledPosition - 1
            if (previousPosition in shuffledIndexes.indices) {
                shuffledPosition = previousPosition
                return shuffledIndexes[shuffledPosition]
            }
            return if (snapshot.loopMode == LoopMode.QUEUE) {
                shuffledPosition = shuffledIndexes.lastIndex
                shuffledIndexes.lastOrNull()
            } else {
                null
            }
        }

        val previousIndex = snapshot.currentIndex - 1
        return when {
            previousIndex in snapshot.queue.indices -> previousIndex
            snapshot.loopMode == LoopMode.QUEUE -> snapshot.queue.lastIndex
            else -> null
        }
    }

    private fun rebuildShuffleOrder(currentIndex: Int) {
        if (snapshot.playOrderMode != PlayOrderMode.SHUFFLE || snapshot.queue.isEmpty()) {
            shuffledIndexes = emptyList()
            shuffledPosition = -1
            return
        }

        val remainingIndexes = snapshot.queue.indices.filter { it != currentIndex }.shuffled()
        shuffledIndexes = listOf(currentIndex) + remainingIndexes
        shuffledPosition = 0
    }

    private fun updateForegroundNotification() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val toggleTitle = if (snapshot.isPlaying) "Pause" else "Play"
        val toggleIcon =
            if (snapshot.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val artworkBitmap = loadArtworkBitmap(snapshot.currentItem?.artworkUriString)
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingIntentFlags(),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(snapshot.currentItem?.displayTitle ?: "Gravi Media Player")
            .setContentText(snapshot.currentItem?.folderPath ?: "Ready")
            .setContentIntent(openIntent)
            .setLargeIcon(artworkBitmap)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(snapshot.isPlaying)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                servicePendingIntent(ACTION_PREVIOUS, 1)
            )
            .addAction(toggleIcon, toggleTitle, servicePendingIntent(ACTION_TOGGLE, 2))
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                servicePendingIntent(ACTION_NEXT, 3)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                servicePendingIntent(ACTION_STOP, 4)
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateMediaSession() {
        val currentItem = snapshot.currentItem
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(
                MediaMetadataCompat.METADATA_KEY_TITLE,
                currentItem?.displayTitle ?: "Gravi Media Player"
            )
            .putString(
                MediaMetadataCompat.METADATA_KEY_ALBUM,
                snapshot.queueTitle ?: currentItem?.folderPath.orEmpty()
            )
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, snapshot.durationMs.toLong())
        loadArtworkBitmap(currentItem?.artworkUriString)?.let {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        }
        mediaSession?.setMetadata(metadataBuilder.build())

        val state =
            if (snapshot.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO or
                            PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, snapshot.positionMs.toLong(), 1f)
                .build()
        )
    }

    private fun loadArtworkBitmap(artworkUriString: String?): Bitmap? {
        val uriString = artworkUriString ?: return null
        return runCatching {
            contentResolver.openInputStream(uriString.toUri())?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        }.getOrNull()
    }

    private fun readPlaybackMetadata(item: AudioItem): AudioItem {
        if (item.mimeType != null && item.bitrate != null && item.durationMs != null && item.lyrics != null) return item

        val retriever = MediaMetadataRetriever()
        return try {
            runCatching {
                retriever.setDataSource(this, item.uri)
                item.copy(
                    mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                    bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toIntOrNull(),
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull(),
                    lyrics = Mp3LyricsReader.readLyrics(this, item.uri),
                )
            }.getOrDefault(item)
        } finally {
            retriever.release()
        }
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int {
        return PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
    }

    private fun notifyListener() {
        listener?.invoke(snapshot)
    }

    private fun safePosition(player: Player): Int {
        return runCatching { player.currentPosition.toInt() }.getOrDefault(0).coerceAtLeast(0)
    }

    private fun safeDuration(player: Player): Int {
        return runCatching { player.duration.toInt() }.getOrDefault(0).coerceAtLeast(0)
    }

    companion object {
        private const val CHANNEL_ID = "music_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_TOGGLE = "com.example.gravimediaplayer.TOGGLE"
        private const val ACTION_NEXT = "com.example.gravimediaplayer.NEXT"
        private const val ACTION_PREVIOUS = "com.example.gravimediaplayer.PREVIOUS"
        private const val ACTION_STOP = "com.example.gravimediaplayer.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackService::class.java)
            )
        }
    }
}