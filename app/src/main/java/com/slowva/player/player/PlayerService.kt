package com.slowva.player.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media.app.NotificationCompat.MediaStyle
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.slowva.player.MainActivity
import com.slowva.player.R
import com.slowva.player.auth.Auth
import com.slowva.player.auth.SessionToken
import com.slowva.player.widget.SlowvaWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the app process (and with it librespot and the audio pump) alive while
 * something is playing, and puts the usual media notification on the lockscreen.
 */
class PlayerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSessionCompat
    private var notificationArt: Bitmap? = null
    private var notificationArtUrl: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        PlayerHub.init(this)
        createChannel()
        session = MediaSessionCompat(this, "slowva").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = PlayerHub.togglePlay().let { }
                override fun onPause() = PlayerHub.togglePlay().let { }
                override fun onSkipToNext() = PlayerHub.next().let { }
                override fun onSkipToPrevious() = PlayerHub.previous().let { }
                override fun onSeekTo(pos: Long) = PlayerHub.seek(pos.toInt()).let { }
            })
            isActive = true
        }
        startForeground(NOTIF_ID, buildNotification())
        watchState()
        watchProcess()
        watchWidgets()
        watchAwake()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> PlayerHub.togglePlay()
            ACTION_NEXT -> PlayerHub.next()
            ACTION_PREV -> PlayerHub.previous()
            ACTION_CONNECT -> PlayerHub.connectLocal(
                transferPlayback = true,
                startPlaying = true,
            )
            ACTION_STOP -> {
                PlayerHub.local.stop()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        holdAwake(cpu = false, net = false)
        scope.cancel()
        session.release()
        PlayerHub.local.stop()
        PlayerHub.localDeviceId.value = null
        SlowvaWidgets.updateAll(this)
        super.onDestroy()
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // with the screen off Android sleeps the CPU and Wi-Fi, which starves librespot
    // and the decoders; hold both only while this phone is actually playing
    private fun watchAwake() = scope.launch {
        PlayerHub.playback.collect { s ->
            val here = s?.deviceId != null &&
                (s.deviceId == PlayerHub.localDeviceId.value || s.deviceId == PlayerHub.LOCAL_FILE_DEVICE)
            val playing = s?.isPlaying == true && (here || PlayerHub.reverseOn.value)
            val streaming = playing && s?.deviceId != PlayerHub.LOCAL_FILE_DEVICE
            holdAwake(cpu = playing, net = streaming)
        }
    }

    private fun holdAwake(cpu: Boolean, net: Boolean) {
        if (cpu && wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "slowva:playback")
                .apply { setReferenceCounted(false); acquire() }
        } else if (!cpu) {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }
        if (net && wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(mode, "slowva:stream")
                .apply { setReferenceCounted(false); acquire() }
        } else if (!net) {
            wifiLock?.takeIf { it.isHeld }?.release()
            wifiLock = null
        }
    }

    private fun watchState() = scope.launch {
        PlayerHub.playback.collectLatest { state ->
            val track = state?.track
            if (track?.art != notificationArtUrl) {
                notificationArtUrl = track?.art
                notificationArt = loadAlbumArt(track?.art)
            }
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track?.name ?: "slowva")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track?.artist.orEmpty())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track?.album.orEmpty())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (track?.durationMs ?: 0).toLong())
            notificationArt?.let { art ->
                metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
            }
            session.setMetadata(metadata.build())
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO
                    )
                    .setState(
                        if (state?.isPlaying == true) PlaybackStateCompat.STATE_PLAYING
                        else PlaybackStateCompat.STATE_PAUSED,
                        (state?.progressMs ?: 0).toLong(), 1f
                    )
                    .build()
            )
            notificationManager().notify(NOTIF_ID, buildNotification())
            SlowvaWidgets.updateAll(this@PlayerService)
        }
    }

    /** Loads the current album cover once so Android media controls can show it. */
    private suspend fun loadAlbumArt(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(this@PlayerService)
                .data(url)
                .allowHardware(false)
                .size(512)
                .build()
            val result = Coil.imageLoader(this@PlayerService).execute(request) as? SuccessResult
                ?: return@withContext null
            result.drawable.toBitmap(512, 512, Bitmap.Config.ARGB_8888)
        }
    }

    /** RemoteViews cannot host a real Canvas, so feed it a small bitmap at 2 fps. */
    private fun watchWidgets() {
        scope.launch(Dispatchers.Default) {
            var lastUpdate = 0L
            PlayerHub.local.spectrum.collect { bars ->
                val now = SystemClock.elapsedRealtime()
                if (PlayerHub.playback.value?.isPlaying == true && now - lastUpdate >= 450L) {
                    SlowvaWidgets.updateCava(this@PlayerService, bars)
                    lastUpdate = now
                }
            }
        }
        scope.launch(Dispatchers.Default) {
            combine(PlayerHub.rate, PlayerHub.reverb) { _, _ -> Unit }
                .collectLatest {
                    delay(120)
                    SlowvaWidgets.updateFx(this@PlayerService)
                }
        }
    }

    /** librespot dies when its token expires or the network drops: bring it back. */
    private fun watchProcess() = scope.launch {
        while (true) {
            delay(10_000)
            val wanted = PlayerHub.localDeviceId.value != null
            val status = PlayerHub.local.status.value
            if (wanted && !PlayerHub.local.isRunning &&
                status != LocalPlayer.Status.STARTING &&
                status != LocalPlayer.Status.FAILED
            ) {
                val token = Auth.streamingToken(this@PlayerService)
                if (token != null || SessionToken.available(this@PlayerService)) {
                    PlayerHub.recoverLocal().join()
                }
            }
        }
    }

    private fun buildNotification(): android.app.Notification {
        val state = PlayerHub.playback.value
        val playing = state?.isPlaying == true
        val content = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun action(icon: Int, title: String, act: String) = NotificationCompat.Action(
            icon, title,
            PendingIntent.getService(
                this, act.hashCode(),
                Intent(this, PlayerService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(notificationArt)
            .setContentTitle(state?.track?.name ?: "slowva")
            .setContentText(state?.track?.artist ?: "connected to Spotify")
            .setContentIntent(content)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREV))
            .addAction(
                action(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pause" else "Play", ACTION_TOGGLE
                )
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun notificationManager() =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        notificationManager().createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL = "slowva-playback"
        private const val NOTIF_ID = 1
        const val ACTION_TOGGLE = "com.slowva.player.TOGGLE"
        const val ACTION_NEXT = "com.slowva.player.NEXT"
        const val ACTION_PREV = "com.slowva.player.PREV"
        const val ACTION_CONNECT = "com.slowva.player.CONNECT"
        const val ACTION_STOP = "com.slowva.player.STOP"
    }
}
