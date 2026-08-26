package com.slowva.player.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.slowva.player.api.Device
import com.slowva.player.api.PlaybackState
import com.slowva.player.api.Spotify
import com.slowva.player.api.Track
import com.slowva.player.auth.Auth
import com.slowva.player.auth.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Application-wide playback state. The UI reads flows from here; the
 * foreground service keeps the process (and therefore librespot) alive.
 */
object PlayerHub {

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val fx = Fx()
    lateinit var local: LocalPlayer
        private set
    lateinit var api: Spotify
        private set

    val playback = MutableStateFlow<PlaybackState?>(null)
    val devices = MutableStateFlow<List<Device>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    val localDeviceId = MutableStateFlow<String?>(null)
    /** true while we are waiting for librespot to show up as a Connect device */
    val connecting = MutableStateFlow(false)
    /** true while Spotify itself is open so the user can pair like in spotc */
    val pairing = MutableStateFlow(false)

    private var polling = false

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        api = Spotify(app)
        local = LocalPlayer(app, fx)
        loadFx()
        startPolling()
    }

    fun say(text: String?) {
        message.value = text
    }

    // ---- the local device ----------------------------------------------

    /**
     * Publishes slowva on Spotify Connect as soon as the app opens. With no
     * cached player credentials, librespot remains in Zeroconf pairing mode;
     * with credentials, it also registers the receiver with Spotify.
     */
    fun startDiscoverable() {
        scope.launch {
            startService()
            if (!local.isRunning) {
                val token = if (SessionToken.available(app)) null else Auth.streamingToken(app)
                local.start(token)
            }
        }
    }

    /** Starts librespot (if needed) and optionally hands playback over to it. */
    fun connectLocal(
        transferPlayback: Boolean = true,
        startPlaying: Boolean = false,
    ) {
        scope.launch {
            val connected = if (SessionToken.available(app)) {
                ensureLocal(transferPlayback, startPlaying)
            } else {
                null
            }
            if (connected == null &&
                (!SessionToken.available(app) || local.failureKind.value == LocalPlayer.FailureKind.AUTH)
            ) {
                pairLocal(startPlaying)
            }
        }
    }

    /** Starts the same Spotify-device pairing flow used by spotc. */
    fun pairFromSpotify(startPlaying: Boolean = true) {
        scope.launch { pairLocal(startPlaying) }
    }

    private suspend fun pairLocal(startPlaying: Boolean): String? {
        if (connecting.value) {
            val deadline = System.currentTimeMillis() + 130_000L
            while (connecting.value && System.currentTimeMillis() < deadline) delay(250)
            return localDeviceId.value
        }
        connecting.value = true
        pairing.value = true
        try {
            startService()
            local.stop()
            localDeviceId.value = null
            Auth.clearPlayerCredentials(app)

            // No token and no cached credentials: librespot stays discoverable
            // and accepts the reusable credentials sent by Spotify Connect.
            local.start(accessToken = null)
            delay(900)
            openSpotifyForPairing()

            if (!awaitCredentials(timeoutMs = 120_000L)) {
                say(local.lastError.value ?: "Pairing timed out — in Spotify open Devices and choose ${local.deviceName}")
                return null
            }

            var id = awaitLocalDevice(timeoutMs = 30_000L)
            if (id == null) {
                refresh()
                val state = playback.value
                if (state?.deviceName == local.deviceName) id = state.deviceId
            }
            if (id != null) {
                localDeviceId.value = id
                if (startPlaying && playback.value?.deviceId != id) {
                    runCatching { api.transfer(id, true) }
                }
                say("Paired with ${local.deviceName}")
            } else {
                // Pairing itself succeeded (credentials were cached), even if
                // a rate-limited Web API token cannot enumerate devices yet.
                say("Player paired. Return to slowva; use your personal Spotify client ID for in-app playback")
            }
            return id
        } finally {
            pairing.value = false
            connecting.value = false
        }
    }

    private suspend fun openSpotifyForPairing() = withContext(Dispatchers.Main) {
        val text = "In Spotify: open Devices and choose ${local.deviceName}"
        say(text)
        Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        val launch = app.packageManager.getLaunchIntentForPackage("com.spotify.music")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com"))
        runCatching { app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { say("Open Spotify → Devices → ${local.deviceName}") }
    }

    private suspend fun ensureLocal(
        transferPlayback: Boolean,
        startPlaying: Boolean = false,
    ): String? {
        if (connecting.value) {
            val deadline = System.currentTimeMillis() + 35_000L
            while (connecting.value && System.currentTimeMillis() < deadline) delay(200)
            return localDeviceId.value
        }
        connecting.value = true
        try {
            var token = Auth.streamingToken(app)
            if (token == null && !SessionToken.available(app)) {
                say("Spotify session expired — sign in again")
                return null
            }
            startService()
            if (!local.isRunning || local.status.value == LocalPlayer.Status.FAILED) {
                if (local.isRunning) local.stop()
                local.start(token)
            }
            if (!SessionToken.available(app)) awaitCredentials(timeoutMs = 12_000)
            var id = awaitLocalDevice()

            // Reusable librespot credentials can be invalidated server-side.
            // Bootstrap a new pair automatically from the OAuth refresh token
            // instead of trapping the user in a permanent "rejected" loop.
            if (id == null && local.failureKind.value == LocalPlayer.FailureKind.AUTH) {
                say("Player credentials expired — renewing them…")
                token = Auth.streamingToken(app, forceRefresh = true)
                if (token == null) {
                    say("Spotify could not renew the player login — sign out and sign in again")
                    return null
                }
                local.stop()
                Auth.clearPlayerCredentials(app)
                local.start(token, preferAccessToken = true)
                awaitCredentials(timeoutMs = 15_000)
                id = awaitLocalDevice(timeoutMs = 30_000)
            }

            if (id == null) {
                say(local.lastError.value ?: "librespot never showed up as a Spotify device")
                return null
            }
            localDeviceId.value = id
            if (transferPlayback) {
                val play = startPlaying || playback.value?.isPlaying == true
                runCatching { api.transfer(id, play) }
                    .onSuccess {
                        say(if (play) "Playing on ${local.deviceName}" else "Connected to ${local.deviceName}")
                    }
                    .onFailure {
                        localDeviceId.value = null
                        say("could not connect this phone: ${it.message?.take(160)}")
                    }
            }
            return localDeviceId.value
        } finally {
            connecting.value = false
        }
    }

    /**
     * Waits for librespot to cache its credentials. Everything after this point
     * can mint session tokens, which are not throttled the way the shared
     * desktop client id is.
     */
    suspend fun awaitCredentials(timeoutMs: Long = 25_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (SessionToken.available(app)) return true
            if (local.status.value == LocalPlayer.Status.FAILED) return false
            delay(500)
        }
        return SessionToken.available(app)
    }

    private suspend fun awaitLocalDevice(timeoutMs: Long = 25_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastLookupError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            if (local.status.value == LocalPlayer.Status.FAILED) return null
            if (!local.isRunning && local.status.value == LocalPlayer.Status.STOPPED) return null
            val found = runCatching { api.devices() }
                .onFailure { lastLookupError = it }
                .getOrNull()
            if (found != null) {
                devices.value = found
                found.filter { it.name == local.deviceName }
                    .maxByOrNull { if (it.isActive) 1 else 0 }
                    ?.let { return it.id }
            }
            delay(1500)
        }
        if (lastLookupError != null) {
            say("could not read Spotify devices: ${lastLookupError?.message?.take(140)}")
        }
        return null
    }

    /**
     * Keeps the process out of Android's cached (freezable) state — the browser
     * sign-in needs us to keep accept()ing on the loopback socket while the
     * browser is in front.
     */
    fun holdForeground() = startService()

    fun releaseForeground() {
        if (!local.isRunning) app.stopService(Intent(app, PlayerService::class.java))
    }

    private fun startService() {
        val intent = Intent(app, PlayerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent)
        else app.startService(intent)
    }

    fun disconnectLocal() {
        local.stop()
        localDeviceId.value = null
        app.stopService(Intent(app, PlayerService::class.java))
    }

    // ---- transport ------------------------------------------------------

    private fun call(what: String, block: suspend () -> Unit) = scope.launch {
        runCatching { block() }
            .onFailure { say("$what: ${it.message?.take(120)}") }
        delay(350)
        refresh()
    }

    fun playTracks(tracks: List<Track>, index: Int, contextUri: String? = null) {
        scope.launch {
            val device = ensureLocal(transferPlayback = false)
            if (device == null) return@launch
            runCatching {
                if (contextUri != null) {
                    api.play(device, contextUri = contextUri, offset = index)
                } else {
                    // no context: send a window of the list so "next" still works
                    val window = tracks.drop(index).take(100).map { it.uri }
                    api.play(device, uris = window, offset = 0)
                }
            }.onFailure { say("could not play: ${it.message?.take(140)}") }
            delay(400)
            refresh()
        }
    }

    fun togglePlay() = scope.launch {
        runCatching {
            val state = playback.value
            if (state?.isPlaying == true) {
                api.pause()
            } else {
                val device = ensureLocal(transferPlayback = false) ?: return@runCatching
                api.resume(device)
            }
        }.onFailure { say("play/pause: ${it.message?.take(120)}") }
        delay(350)
        refresh()
    }

    fun next() = call("next") { api.next() }
    fun previous() = call("prev") { api.previous() }
    fun seek(ms: Int) = call("seek") { api.seek(ms) }
    fun setVolume(percent: Int) = call("volume") { api.setVolume(percent) }
    fun toggleShuffle() = call("shuffle") { api.setShuffle(!(playback.value?.shuffle ?: false)) }
    fun cycleRepeat() = call("repeat") {
        val next = when (playback.value?.repeat) {
            "off" -> "context"
            "context" -> "track"
            else -> "off"
        }
        api.setRepeat(next)
    }

    fun transferTo(device: Device) = call("transfer") { api.transfer(device.id, true) }

    fun refreshDevices() = scope.launch {
        runCatching { devices.value = api.devices() }
    }

    suspend fun refresh() {
        runCatching { api.state() }.onSuccess { playback.value = it }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        scope.launch {
            while (true) {
                if (Auth.isLoggedIn(app)) refresh()
                delay(if (playback.value?.isPlaying == true) 3000 else 12000)
            }
        }
    }

    // ---- effects --------------------------------------------------------

    /** UI-observable mirrors of [fx]; the DSP thread reads the Fx fields. */
    val speed = MutableStateFlow(1.0f)
    val reverb = MutableStateFlow(0.0f)
    val retuneIndex = MutableStateFlow(0)
    val bassGain = MutableStateFlow(0.0f)
    val bassFreq = MutableStateFlow(120.0f)

    /** What you actually hear: the slow slider times the tuning ratio. */
    val rate = MutableStateFlow(1.0f)

    fun setSpeed(value: Float) {
        speed.value = value
        fx.speed = value
        rate.value = fx.rate
        saveFx()
    }

    /** Drags on the slow bar, which shows the combined rate, not the raw speed. */
    fun setRate(value: Float) {
        val ratio = RETUNE_PRESETS.getOrNull(retuneIndex.value)?.ratio ?: 1f
        setSpeed(value / ratio)
    }

    fun setReverb(value: Float) {
        reverb.value = value
        fx.reverb = value
        saveFx()
    }

    fun setBass(gainDb: Float, freq: Float) {
        bassGain.value = gainDb
        bassFreq.value = freq
        fx.bassGainDb = gainDb
        fx.bassFreq = freq
        saveFx()
    }

    fun setRetune(index: Int) {
        val preset = RETUNE_PRESETS.getOrNull(index) ?: return
        retuneIndex.value = index
        fx.retune = preset.ratio
        rate.value = fx.rate
        if (preset.reverb > 0f) setReverb(preset.reverb) else saveFx()
    }

    fun resetFx() {
        setSpeed(1f)
        setReverb(0f)
        setRetune(0)
        setBass(0f, 120f)
    }

    private fun saveFx() {
        if (!::app.isInitialized) return
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("speed", speed.value)
            .putFloat("reverb", reverb.value)
            .putInt("retune", retuneIndex.value)
            .putFloat("bass_gain", bassGain.value)
            .putFloat("bass_freq", bassFreq.value)
            .apply()
    }

    private fun loadFx() {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        speed.value = p.getFloat("speed", 1f)
        reverb.value = p.getFloat("reverb", 0f)
        retuneIndex.value = p.getInt("retune", 0)
        bassGain.value = p.getFloat("bass_gain", 0f)
        bassFreq.value = p.getFloat("bass_freq", 120f)
        fx.speed = speed.value
        fx.reverb = reverb.value
        fx.retune = RETUNE_PRESETS.getOrNull(retuneIndex.value)?.ratio ?: 1f
        fx.bassGainDb = bassGain.value
        fx.bassFreq = bassFreq.value
        rate.value = fx.rate
    }

    private const val PREFS = "slowva-fx"
}
