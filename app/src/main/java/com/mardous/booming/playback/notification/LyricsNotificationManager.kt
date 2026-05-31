package com.mardous.booming.playback.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.mardous.booming.R
import com.mardous.booming.data.local.repository.LyricsRepository
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.LyricsSource
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import com.mardous.booming.ui.screen.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LyricsNotificationManager(
    private val context: Context,
    private val lyricsRepository: LyricsRepository
) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentSong: Song = Song.emptySong
    private var currentLyrics: SyncedLyrics? = null
    private var currentLineIndex: Int = -1
    private var progressUpdateJob: Job? = null

    private var player: Player? = null

    companion object {
        const val LYRICS_NOTIFICATION_ID = 2
        const val LYRICS_CHANNEL_ID = "lyrics_notification"

        private const val PROGRESS_UPDATE_INTERVAL = 100L
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (notificationManager.getNotificationChannel(LYRICS_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                LYRICS_CHANNEL_ID,
                context.getString(R.string.lyrics_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.lyrics_notification_channel_description)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun setPlayer(player: Player) {
        this.player = player
    }

    fun startLyricsNotification(song: Song) {
        if (!hasNotificationPermission()) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                context,
                LYRICS_NOTIFICATION_ID,
                buildNotification(song, null, null, 0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            context.startForegroundService(Intent(context, LyricsNotificationService::class.java).apply {
                putExtra(EXTRA_SONG_ID, song.id)
            })
        }

        currentSong = song
        loadAndDisplayLyrics(song)
        startProgressUpdates()
    }

    fun stopLyricsNotification() {
        stopProgressUpdates()
        notificationManager.cancel(LYRICS_NOTIFICATION_ID)
        currentSong = Song.emptySong
        currentLyrics = null
        currentLineIndex = -1
    }

    fun updateSong(song: Song) {
        if (song.id != currentSong.id) {
            currentSong = song
            loadAndDisplayLyrics(song)
        }
    }

    private fun loadAndDisplayLyrics(song: Song) {
        serviceScope.launch(Dispatchers.IO) {
            val syncedLyrics = getBestLyricsFromSources(song)
            mainHandler.post {
                currentLyrics = syncedLyrics
                updateNotificationProgress()
            }
        }
    }

    private suspend fun getBestLyricsFromSources(song: Song): SyncedLyrics? {
        val sources = listOf(
            LyricsSource.File,
            LyricsSource.Embedded,
            LyricsSource.Downloaded
        )

        for (source in sources) {
            val rawLyrics = when (source) {
                LyricsSource.File -> lyricsRepository.fileLyrics(song)
                LyricsSource.Embedded -> lyricsRepository.embeddedLyrics(song)
                LyricsSource.Downloaded -> lyricsRepository.storedLyrics(song, true)
            }

            if (rawLyrics != null) {
                val lyrics = lyricsRepository.parseRawLyrics(song, rawLyrics)
                if (lyrics?.hasContent == true) {
                    return lyrics
                }
            }
        }
        return null
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressUpdateJob = serviceScope.launch {
            while (true) {
                updateNotificationProgress()
                kotlinx.coroutines.delay(PROGRESS_UPDATE_INTERVAL)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = null
    }

    private fun updateNotificationProgress() {
        if (!hasNotificationPermission()) {
            return
        }

        val player = this.player ?: return
        val lyrics = currentLyrics ?: return

        val currentPositionMs = player.currentPosition
        val durationMs = player.duration.coerceAtLeast(1)
        val progress = ((currentPositionMs.toFloat() / durationMs) * 100).toInt().coerceIn(0, 100)

        val lineIndex = findCurrentLineIndex(lyrics, currentPositionMs)

        if (lineIndex != currentLineIndex || lineIndex == -1) {
            currentLineIndex = lineIndex
            val currentLine = if (lineIndex >= 0 && lineIndex < lyrics.lines.size) {
                lyrics.lines[lineIndex].content.content
            } else null

            val nextLine = if (lineIndex >= 0 && lineIndex + 1 < lyrics.lines.size) {
                lyrics.lines[lineIndex + 1].content.content
            } else null

            val notification = buildNotification(currentSong, currentLine, nextLine, progress)
            notificationManager.notify(LYRICS_NOTIFICATION_ID, notification)
        } else {
            val notification = buildNotification(currentSong, null, null, progress)
            notificationManager.notify(LYRICS_NOTIFICATION_ID, notification)
        }
    }

    private fun findCurrentLineIndex(lyrics: SyncedLyrics, positionMs: Long): Int {
        for (i in lyrics.lines.indices) {
            val line = lyrics.lines[i]
            if (positionMs >= line.startAt && positionMs < line.end) {
                return i
            }
        }
        return -1
    }

    private fun buildNotification(
        song: Song,
        currentLine: String?,
        nextLine: String?,
        progress: Int
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = currentLine ?: context.getString(R.string.no_lyrics_found)
        val subText = nextLine ?: ""

        return NotificationCompat.Builder(context, LYRICS_CHANNEL_ID)
            .setContentTitle(song.title)
            .setContentText(contentText)
            .setSubText(subText)
            .setSmallIcon(R.drawable.ic_stat_music_playback)
            .setContentIntent(pendingIntent)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$contentText\n$subText")
                .setBigContentTitle(song.title)
                .setSummaryText(subText))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setRequestPromotedOngoing(true)
                }
            }
            .build()
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun release() {
        stopProgressUpdates()
        serviceScope.cancel()
    }
}

class LyricsNotificationService : Service() {

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(LyricsNotificationManager.LYRICS_NOTIFICATION_ID, createEmptyNotification())
        return START_NOT_STICKY
    }

    private fun createEmptyNotification(): Notification {
        return NotificationCompat.Builder(this, LyricsNotificationManager.LYRICS_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_stat_music_playback)
            .build()
    }

    companion object {
        const val EXTRA_SONG_ID = "song_id"
    }
}
