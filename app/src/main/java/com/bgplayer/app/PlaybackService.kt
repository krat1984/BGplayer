package com.bgplayer.app

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.bgplayer.core.BgLog
import com.bgplayer.core.Chapter
import com.bgplayer.core.ChapterNavigator
import com.bgplayer.core.ChapterReader
import com.bgplayer.core.HeadsetButtonDetector
import com.bgplayer.core.RandomAccessSource
import com.bgplayer.core.SleepTimer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.FileInputStream
import java.util.concurrent.Executors

/**
 * Owns the player. Lives as a foreground media service, so audio keeps playing
 * with the screen off or the app in background.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        private const val TAG = "Service"
    }

    private lateinit var exo: ExoPlayer
    private var session: MediaSession? = null
    private lateinit var detector: HeadsetButtonDetector
    private lateinit var sleepTimer: SleepTimer
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "chapters") }

    /** In-memory only (no cache between launches): mediaId -> chapters. */
    private val chapterCache = HashMap<String, List<Chapter>>()

    @Volatile var sleepRemainingMs: Long = -1
        private set

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        BgLog.i(TAG, "onCreate")
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)    // keep CPU awake with the screen off
            .build()
        exo.repeatMode = if (Prefs.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        BgLog.i(TAG, "repeat mode from settings: ${repeatName(exo.repeatMode)}")
        exo.addListener(playerListener)

        val scheduler = HandlerScheduler(main)
        detector = HeadsetButtonDetector(scheduler) { action ->
            BgLog.i(TAG, "headset action $action")
            when (action) {
                HeadsetButtonDetector.Action.PLAY_PAUSE -> togglePlayPause("headset")
                HeadsetButtonDetector.Action.NEXT_CHAPTER -> nextChapter("headset")
                HeadsetButtonDetector.Action.PREVIOUS_CHAPTER -> previousChapter("headset")
                null -> {}
            }
        }
        sleepTimer = SleepTimer(scheduler, object : SleepTimer.Listener {
            override fun onVolume(volume: Float) {
                exo.volume = volume
            }

            override fun onExpired() {
                BgLog.i(TAG, "sleep timer expired -> pause")
                exo.pause()
            }

            override fun onTick(remainingMs: Long) {
                sleepRemainingMs = remainingMs
            }
        })

        val activityIntent = Intent(this, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, ChapterPlayer(exo))
            .setCallback(sessionCallback)
            .setSessionActivity(pending)
            .build()
        PlayerHub.service = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        BgLog.d(TAG, "onGetSession from ${controllerInfo.packageName}")
        return session
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val keep = exo.playWhenReady && exo.mediaItemCount > 0
        BgLog.i(TAG, "onTaskRemoved, keepPlaying=$keep")
        if (!keep) stopSelf()
    }

    override fun onDestroy() {
        BgLog.i(TAG, "onDestroy")
        PlayerHub.service = null
        sleepTimer.cancel()
        detector.reset()
        session?.release()
        session = null
        exo.removeListener(playerListener)
        exo.release()
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ public API (via PlayerHub)

    fun currentChapters(): List<Chapter> {
        val id = exo.currentMediaItem?.mediaId ?: return emptyList()
        return chapterCache[id] ?: emptyList()
    }

    fun currentPositionMs(): Long = exo.currentPosition

    fun startSleepTimer(minutes: Int) {
        BgLog.i(TAG, "sleep timer: $minutes min")
        sleepTimer.start(minutes * 60_000L)
    }

    fun cancelSleepTimer() = sleepTimer.cancel()

    fun setVideoEnabled(enabled: Boolean) {
        BgLog.i(TAG, "video track ${if (enabled) "enabled" else "disabled (background)"}")
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    fun seekToChapter(index: Int) {
        val ch = currentChapters().getOrNull(index) ?: return
        BgLog.i(TAG, "seek to chapter #$index '${ch.title}' at ${ch.startMs}ms")
        exo.seekTo(ch.startMs)
    }

    fun nextChapter(source: String) {
        val chapters = chaptersForNavigation()
        val a = ChapterNavigator.next(chapters, exo.currentPosition, exo.hasNextMediaItem())
        BgLog.i(TAG, "next chapter [$source] -> $a")
        apply(a)
    }

    fun previousChapter(source: String) {
        val chapters = chaptersForNavigation()
        val a = ChapterNavigator.previous(chapters, exo.currentPosition, exo.hasPreviousMediaItem())
        BgLog.i(TAG, "previous chapter [$source] -> $a")
        apply(a)
    }

    fun togglePlayPause(source: String) {
        BgLog.i(TAG, "play/pause [$source], playing=${exo.isPlaying} state=${stateName(exo.playbackState)}")
        if (exo.isPlaying) {
            exo.pause()
        } else {
            if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
            if (exo.playbackState == Player.STATE_ENDED) exo.seekToDefaultPosition()
            exo.play()
        }
    }

    /**
     * Raw media key from the session or from the activity (when it has focus).
     * @return true if consumed.
     */
    fun handleMediaKey(ev: KeyEvent, source: String): Boolean {
        BgLog.v(TAG, "key [$source] ${KeyEvent.keyCodeToString(ev.keyCode)} " +
                "action=${if (ev.action == KeyEvent.ACTION_DOWN) "DOWN" else if (ev.action == KeyEvent.ACTION_UP) "UP" else ev.action} " +
                "repeat=${ev.repeatCount} flags=0x${Integer.toHexString(ev.flags)} " +
                "downTime=${ev.downTime} eventTime=${ev.eventTime}")
        when (ev.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                when (ev.action) {
                    KeyEvent.ACTION_DOWN ->
                        detector.onKeyDown(ev.repeatCount, (ev.flags and KeyEvent.FLAG_LONG_PRESS) != 0)
                    KeyEvent.ACTION_UP ->
                        if ((ev.flags and KeyEvent.FLAG_CANCELED) != 0) {
                            BgLog.d(TAG, "UP with FLAG_CANCELED (system took the press) -> reset")
                            detector.reset()
                        } else {
                            detector.onKeyUp()
                        }
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) nextChapter("$source:NEXT")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) previousChapter("$source:PREV")
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------ internals

    private fun chaptersForNavigation(): List<Chapter> {
        val id = exo.currentMediaItem?.mediaId
        val ch = id?.let { chapterCache[it] }
        if (id != null && ch == null) BgLog.w(TAG, "chapters for current item not loaded yet")
        return ch ?: emptyList()
    }

    private fun apply(a: ChapterNavigator.Action) {
        when (a.kind) {
            ChapterNavigator.Kind.SEEK -> exo.seekTo(a.positionMs)
            ChapterNavigator.Kind.NEXT_ITEM -> exo.seekToNextMediaItem()
            ChapterNavigator.Kind.PREVIOUS_ITEM -> exo.seekToPreviousMediaItem()
            null -> {}
        }
    }

    private fun loadChapters(item: MediaItem?) {
        item ?: return
        val id = item.mediaId
        if (chapterCache.containsKey(id)) {
            BgLog.d(TAG, "chapters already known for $id: ${chapterCache[id]?.size}")
            return
        }
        val uri = item.localConfiguration?.uri ?: Uri.parse(id)
        val name = item.mediaMetadata.title?.toString() ?: uri.lastPathSegment ?: id
        BgLog.d(TAG, "loading chapters for '$name' ($uri)")
        io.execute {
            val result = try {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { input ->
                        ChapterReader.read(RandomAccessSource.OfChannel(input.channel), name)
                    }
                } ?: emptyList()
            } catch (t: Throwable) {
                BgLog.e(TAG, "cannot open '$name' for chapters", t)
                emptyList()
            }
            main.post { chapterCache[id] = result }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            BgLog.i(TAG, "item -> '${mediaItem?.mediaMetadata?.title}' index=${exo.currentMediaItemIndex}/" +
                    "${exo.mediaItemCount} reason=${transitionName(reason)}")
            loadChapters(mediaItem)
        }

        override fun onPlaybackStateChanged(state: Int) {
            BgLog.i(TAG, "state ${stateName(state)} pos=${exo.currentPosition}")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            BgLog.i(TAG, "isPlaying=$isPlaying")
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            BgLog.i(TAG, "playWhenReady=$playWhenReady reason=${pwrReasonName(reason)}")
        }

        override fun onPlaybackSuppressionReasonChanged(reason: Int) {
            BgLog.i(TAG, "suppression reason=$reason")
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            BgLog.i(TAG, "repeat mode -> ${repeatName(repeatMode)}")
            Prefs.loop = repeatMode == Player.REPEAT_MODE_ONE
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
        ) {
            BgLog.v(TAG, "discontinuity reason=$reason ${oldPosition.positionMs}ms -> ${newPosition.positionMs}ms " +
                    "(item ${oldPosition.mediaItemIndex} -> ${newPosition.mediaItemIndex})")
        }

        override fun onTracksChanged(tracks: Tracks) {
            val desc = tracks.groups.joinToString { g ->
                "${trackTypeName(g.type)}${if (g.isSelected) "*" else ""}"
            }
            BgLog.d(TAG, "tracks: $desc")
        }

        override fun onPlayerError(error: PlaybackException) {
            BgLog.e(TAG, "player error ${error.errorCodeName}", error)
        }
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession, controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            BgLog.d(TAG, "controller connected: ${controller.packageName} uid=${controller.uid}")
            return super.onConnect(session, controller)
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            BgLog.d(TAG, "controller disconnected: ${controller.packageName}")
        }

        /** MediaItems sent by a controller lose their URI; restore it from requestMetadata / mediaId. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri ?: Uri.parse(item.mediaId)
                item.buildUpon().setUri(uri).build()
            }.toMutableList()
            BgLog.i(TAG, "add ${resolved.size} item(s) from ${controller.packageName}")
            return Futures.immediateFuture(resolved)
        }

        override fun onMediaButtonEvent(
            session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent
        ): Boolean {
            val ev = keyEventFrom(intent)
            if (ev == null) {
                BgLog.w(TAG, "media button intent without KeyEvent: $intent")
                return false
            }
            return handleMediaKey(ev, "session:${controllerInfo.packageName}")
        }
    }

    @Suppress("DEPRECATION")
    private fun keyEventFrom(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)

    /**
     * Notification / Bluetooth "next"/"previous" commands arrive as seekToNext/seekToPrevious:
     * they are mapped to chapter navigation.
     */
    private inner class ChapterPlayer(player: ExoPlayer) : ForwardingPlayer(player) {
        override fun seekToNext() = nextChapter("command:seekToNext")
        override fun seekToPrevious() = previousChapter("command:seekToPrevious")
    }
}

/** Same-process access from the activity to the running service. */
object PlayerHub {
    @Volatile var service: PlaybackService? = null
}

internal fun stateName(s: Int) = when (s) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> "?$s"
}

internal fun repeatName(m: Int) = when (m) {
    Player.REPEAT_MODE_OFF -> "OFF"
    Player.REPEAT_MODE_ONE -> "ONE"
    Player.REPEAT_MODE_ALL -> "ALL"
    else -> "?$m"
}

private fun transitionName(r: Int) = when (r) {
    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
    else -> "?$r"
}

private fun pwrReasonName(r: Int) = when (r) {
    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER"
    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "HEADPHONES_UNPLUGGED"
    Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
    Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_ITEM"
    else -> "?$r"
}

private fun trackTypeName(t: Int) = when (t) {
    C.TRACK_TYPE_VIDEO -> "video"
    C.TRACK_TYPE_AUDIO -> "audio"
    C.TRACK_TYPE_TEXT -> "text"
    C.TRACK_TYPE_METADATA -> "metadata"
    else -> "type$t"
}
