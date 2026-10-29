package com.slowva.player.player

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.slowva.player.player.fx.FxProcessorChain
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.slowva.player.R
import com.slowva.player.SlowvaApp
import com.slowva.player.net.NetworkGate
import com.google.common.util.concurrent.Futures
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.slowva.player.player.fx.Moods
import com.slowva.player.widget.SlowvaWidgets
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** ExoPlayer + MediaLibrarySession; media3 provides the notification and lock-screen controls, Android Auto browses [AutoLibrary]. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var playback: PlaybackController? = null
    private var library: AutoLibrary? = null
    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        PlayerHub.init(this)
        com.slowva.player.player.sound.SoundEngine.init(this)
        // effects run in our own processor chain, after ExoPlayer's speed stage
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessorChain(FxProcessorChain(PlayerHub.fx) { PlayerHub.spectrum.value = it })
                .build()
        }
        // http(s) goes through the network gate; files, content:// and assets stay local; online schemes go to their own source
        val dataSources = RoutingDataSourceFactory(DefaultDataSource.Factory(this, OkHttpDataSource.Factory(NetworkGate.mediaClient)), StreamRoutes.all)
        // streams without a length stay seekable for constant-bitrate MP3 and AAC
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources, extractors))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // keeps the CPU awake with the screen off; without it playback dies in deep sleep
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // one unreadable file should not end the session: skip it and keep going
        var audioRetries = 0
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) audioRetries = 0
            }

            override fun onPlayerError(error: PlaybackException) {
                // the audio output died (Bluetooth switch, call, audio service restart): pick up where it stopped
                if (error.errorCode in PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED..PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED &&
                    audioRetries++ < 3
                ) {
                    player.prepare()
                    player.play()
                } else if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })

        val app = application as SlowvaApp
        playback = PlaybackController(this, player, app.repository)
        library = AutoLibrary(app.repository)
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) })
        mediaSession = MediaLibrarySession.Builder(this, player, SessionCallback()).build()

        // home screen widgets: redrawn when the song, play state or mode changes, not on every tick
        SlowvaWidgets.attach(this, player) { action, args -> playback?.handle(action, args) }
        scope.launch {
            PlayerHub.playback
                .distinctUntilChanged { a, b -> a?.track?.id == b?.track?.id && a?.isPlaying == b?.isPlaying }
                .collect { SlowvaWidgets.update(this@PlaybackService, it) }
        }
        scope.launch { Moods.mood.collect { SlowvaWidgets.redraw(this@PlaybackService) } }
    }

    /** Only Slowva itself may send most custom commands; next/previous are open to all. */
    private inner class SessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val allowed = if (controller.packageName == packageName) Cmd.ALL else Cmd.PUBLIC
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .apply { allowed.forEach { add(SessionCommand(it, Bundle.EMPTY)) } }
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val action = customCommand.customAction
            val ok = (controller.packageName == packageName || action in Cmd.PUBLIC) &&
                playback?.handle(action, args) == true
            return Futures.immediateFuture(SessionResult(if (ok) SessionResult.RESULT_SUCCESS else SessionError.ERROR_NOT_SUPPORTED))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            library?.let { LibraryResult.ofItem(it.root, params) } ?: LibraryResult.ofError(SessionError.ERROR_INVALID_STATE)
        )

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = libraryAsync {
            item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = libraryAsync {
            children(parentId)?.let { LibraryResult.ofItemList(AutoIds.page(it, page, pageSize), params) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        /** A tapped Auto item goes through the app's own queue commands, so reverse stays consistent. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val id = mediaItems.singleOrNull()?.mediaId
            if (id == null || AutoIds.parseLeaf(id) == null) return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            scope.launch { library?.playCommand(id)?.let { (action, args) -> playback?.handle(action, args) } }
            // the controller loads the player itself; a failed future makes the session skip its own setMediaItems
            return Futures.immediateFailedFuture(UnsupportedOperationException("played through PlaybackController"))
        }

        private fun <T : Any> libraryAsync(get: suspend AutoLibrary.() -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
            val future = SettableFuture.create<LibraryResult<T>>()
            val lib = library ?: return Futures.immediateFuture(LibraryResult.ofError<T>(SessionError.ERROR_INVALID_STATE))
            scope.launch { runCatching { lib.get() }.onSuccess { future.set(it) }.onFailure { future.setException(it) } }
            return future
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int = when (intent?.action) {
        ACTION_HOLD -> { holdForeground(); START_NOT_STICKY }
        ACTION_RELEASE -> { stopForeground(STOP_FOREGROUND_REMOVE); START_NOT_STICKY }
        else -> super.onStartCommand(intent, flags, startId)
    }

    /** Keeps the process running while a Spotify sign-in waits for the browser to come back. */
    private fun holdForeground() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(HOLD_CHANNEL, "Sign-in", android.app.NotificationManager.IMPORTANCE_LOW))
        val n = androidx.core.app.NotificationCompat.Builder(this, HOLD_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Signing in to Spotify")
            .setContentText("Finish in the browser, then come back")
            .setOngoing(true)
            .build()
        androidx.core.app.ServiceCompat.startForeground(this, HOLD_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    companion object {
        private const val ACTION_HOLD = "com.slowva.player.HOLD"
        private const val ACTION_RELEASE = "com.slowva.player.RELEASE"
        private const val HOLD_CHANNEL = "signin"
        private const val HOLD_ID = 4207

        fun hold(ctx: Context) = androidx.core.content.ContextCompat.startForegroundService(ctx, android.content.Intent(ctx, PlaybackService::class.java).setAction(ACTION_HOLD))
        fun release(ctx: Context) { runCatching { ctx.startService(android.content.Intent(ctx, PlaybackService::class.java).setAction(ACTION_RELEASE)) } }
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        SlowvaWidgets.detach(this)
        playback?.release()
        playback = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        library = null
        scope.cancel()
        super.onDestroy()
    }
}
