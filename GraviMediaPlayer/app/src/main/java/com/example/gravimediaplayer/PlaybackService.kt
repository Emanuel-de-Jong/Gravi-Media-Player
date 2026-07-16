package com.example.gravimediaplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class PlaybackService : Service() {
    private val binder = PlaybackBinder()
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var listener: ((PlaybackSnapshot) -> Unit)? = null
    private var snapshot = PlaybackSnapshot()
    private var shuffledIndexes = emptyList<Int>()
    private var shuffledPosition = -1

    private val progressUpdater = object : Runnable {
        override fun run() {
            mediaPlayer?.let {
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
        mediaSession = MediaSessionCompat(this, "Gravi Media Player")
        handler.post(progressUpdater)
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
        mediaPlayer?.release()
        mediaSession?.release()
        super.onDestroy()
    }

    fun setListener(newListener: ((PlaybackSnapshot) -> Unit)?) {
        listener = newListener
        notifyListener()
    }

    fun getSnapshot(): PlaybackSnapshot = snapshot

    fun playQueue(queue: List<AudioItem>, startIndex: Int) {
        if (queue.isEmpty()) {
            snapshot = snapshot.copy(errorMessage = "No audio files found.")
            notifyListener()
            return
        }

        val safeIndex = startIndex.coerceIn(queue.indices)
        snapshot = snapshot.copy(
            queue = queue,
            currentIndex = safeIndex,
            positionMs = 0,
            durationMs = 0,
            errorMessage = null,
        )
        rebuildShuffleOrder(safeIndex)
        playIndex(safeIndex)
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            snapshot = snapshot.copy(isPlaying = false, positionMs = safePosition(player))
        } else {
            player.start()
            snapshot = snapshot.copy(isPlaying = true, positionMs = safePosition(player))
        }
        updateForegroundNotification()
        notifyListener()
    }

    fun playNext() {
        val nextIndex = getNextIndex() ?: return
        playIndex(nextIndex)
    }

    fun playPrevious() {
        val player = mediaPlayer
        if (player != null && safePosition(player) > 3000) {
            seekTo(0)
            return
        }

        val previousIndex = getPreviousIndex() ?: return
        playIndex(previousIndex)
    }

    fun seekTo(positionMs: Int) {
        val player = mediaPlayer ?: return
        player.seekTo(positionMs.coerceIn(0, safeDuration(player)))
        snapshot = snapshot.copy(positionMs = safePosition(player))
        notifyListener()
    }

    fun setShuffle(enabled: Boolean) {
        snapshot = snapshot.copy(shuffleEnabled = enabled)
        rebuildShuffleOrder(snapshot.currentIndex)
        notifyListener()
    }

    fun setLoop(enabled: Boolean) {
        snapshot = snapshot.copy(loopEnabled = enabled)
        notifyListener()
    }

    private fun playIndex(index: Int) {
        val item = snapshot.queue.getOrNull(index) ?: return
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@PlaybackService, item.uri)
            setOnPreparedListener {
                it.start()
                snapshot = snapshot.copy(
                    currentIndex = index,
                    isPlaying = true,
                    positionMs = 0,
                    durationMs = safeDuration(it),
                    errorMessage = null,
                )
                mediaSession?.isActive = true
                updateForegroundNotification()
                notifyListener()
            }
            setOnCompletionListener {
                if (snapshot.loopEnabled) {
                    playIndex(snapshot.currentIndex)
                } else {
                    val nextIndex = getNextIndex()
                    if (nextIndex == null) {
                        snapshot = snapshot.copy(isPlaying = false, positionMs = safeDuration(it))
                        updateForegroundNotification()
                        notifyListener()
                    } else {
                        playIndex(nextIndex)
                    }
                }
            }
            setOnErrorListener { _, _, _ ->
                snapshot =
                    snapshot.copy(isPlaying = false, errorMessage = "Unable to play this file.")
                updateForegroundNotification()
                notifyListener()
                true
            }
            prepareAsync()
        }
        snapshot =
            snapshot.copy(currentIndex = index, isPlaying = false, positionMs = 0, durationMs = 0)
        notifyListener()
    }

    private fun stopPlayback() {
        mediaPlayer?.pause()
        snapshot = snapshot.copy(isPlaying = false)
        updateForegroundNotification()
        notifyListener()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun getNextIndex(): Int? {
        if (snapshot.queue.isEmpty()) return null

        if (snapshot.shuffleEnabled) {
            if (shuffledIndexes.isEmpty()) rebuildShuffleOrder(snapshot.currentIndex)
            val nextPosition = shuffledPosition + 1
            if (nextPosition in shuffledIndexes.indices) {
                shuffledPosition = nextPosition
                return shuffledIndexes[shuffledPosition]
            }
            return null
        }

        val nextIndex = snapshot.currentIndex + 1
        return if (nextIndex in snapshot.queue.indices) nextIndex else null
    }

    private fun getPreviousIndex(): Int? {
        if (snapshot.queue.isEmpty()) return null

        if (snapshot.shuffleEnabled) {
            val previousPosition = shuffledPosition - 1
            if (previousPosition in shuffledIndexes.indices) {
                shuffledPosition = previousPosition
                return shuffledIndexes[shuffledPosition]
            }
            return null
        }

        val previousIndex = snapshot.currentIndex - 1
        return if (previousIndex in snapshot.queue.indices) previousIndex else null
    }

    private fun rebuildShuffleOrder(currentIndex: Int) {
        if (!snapshot.shuffleEnabled || snapshot.queue.isEmpty()) {
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
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingIntentFlags(),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(snapshot.currentItem?.title ?: "Gravi Media Player")
            .setContentText(snapshot.currentItem?.folderPath ?: "Ready")
            .setContentIntent(openIntent)
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
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
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

    private fun safePosition(player: MediaPlayer): Int {
        return runCatching { player.currentPosition }.getOrDefault(0)
    }

    private fun safeDuration(player: MediaPlayer): Int {
        return runCatching { player.duration }.getOrDefault(0).coerceAtLeast(0)
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