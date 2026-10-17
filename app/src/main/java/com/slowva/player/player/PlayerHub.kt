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
import kotlinx.coroutines.flow.StateFlow
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
    lateinit var reverse: ReversePlayer
        private set
    private val noReverse = MutableStateFlow<ReversePlayer.State?>(null)
    val reverseState: StateFlow<ReversePlayer.State?> get() = if (::reverse.isInitialized) reverse.state else noReverse
    lateinit var files: FilePlayer
        private set
    /** a local file is the current track (playing or paused) */
    val fileOn = MutableStateFlow(false)
    /** reverse mode is on; it stays on across tracks until switched off */
    val reverseOn = MutableStateFlow(false)
    private var queue: List<Track> = emptyList()
    private var queueIndex = 0
    lateinit var local: LocalPlayer
        private set
    private val noSpectrum = MutableStateFlow(FloatArray(SpectrumAnalyzer.BAR_COUNT))
    val spectrum: StateFlow<FloatArray> get() = if (::local.isInitialized) local.spectrum else noSpectrum
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
        files = FilePlayer(app, fx).apply {
            onSpectrum = { local.spectrum.value = it }
            onFinished = { scope.launch { fileStep(+1, auto = true) } }
        }
        scope.launch { LocalLibrary.scan(app) }
        scope.launch { files.state.collect { if (fileOn.value && !reverseOn.value) mirrorFile() } }
        reverse = ReversePlayer(app, fx).apply {
            onSpectrum = { local.spectrum.value = it }
            onFinished = { scope.launch { reverseStep(+1) } }
        }
        loadFx()
        startPolling()
        scope.launch { reverse.state.collect { if (reverseOn.value) mirrorReverse() } }
    }

    fun say(text: String?) {
        message.value = text
    }

    // ---- the local device ----------------------------------------------

    /** Starts librespot so slowva shows up in Spotify Connect (Zeroconf until credentials exist). */
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

    /** Waits until librespot has cached credentials, which session tokens need. */
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

    /** Keeps the process from being frozen while the browser sign-in waits on the loopback socket. */
    fun holdForeground() = startService()

    fun releaseForeground() {
        if (!local.isRunning && !fileOn.value && !reverseOn.value) app.stopService(Intent(app, PlayerService::class.java))
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

    private fun call(
        what: String,
        recover: (suspend (device: String) -> Unit)? = null,
        block: suspend () -> Unit,
    ) = scope.launch {
        runCatching { block() }
            .recoverCatching { e ->
                if (recover == null || !lostDevice(e)) throw e
                val device = ensureLocal(transferPlayback = false) ?: throw e
                recover(device)
            }
            .onFailure { say("$what: ${explain(it)}") }
        delay(350)
        refresh()
    }

    private fun lostDevice(e: Throwable) = e is Spotify.ApiException && e.noActiveDevice

    private fun explain(e: Throwable) =
        if (lostDevice(e)) "Spotify lost this phone — tap a track to start again"
        else e.message?.take(120).orEmpty()

    /** last state Spotify reported for real tracks, so a restarted librespot can carry on */
    @Volatile private var lastSpotify: PlaybackState? = null
    @Volatile private var lastSpotifyAt = 0L

    /** Puts the last Spotify track back on [device] after Spotify forgot the old session. */
    private suspend fun restoreOn(device: String, positionMs: Int? = null, play: Boolean = true) {
        val last = lastSpotify ?: playback.value?.takeIf { it.deviceId != LOCAL_FILE_DEVICE }
        val track = last?.track?.takeIf { !LocalLibrary.isLocal(it) }
            ?: throw Spotify.ApiException(404, "no active device", "NO_ACTIVE_DEVICE")
        val from = positionMs ?: run {
            val ran = if (last.isPlaying) System.currentTimeMillis() - lastSpotifyAt else 0L
            (last.progressMs + ran).coerceAtMost(track.durationMs - 1000L).coerceAtLeast(0L).toInt()
        }
        // transfer keeps the album or playlist; fall back to the queue when Spotify has nothing left
        runCatching { api.transfer(device, play) }
        delay(800)
        val now = runCatching { api.state() }.getOrNull()
        if (now?.deviceId == device && now.track?.id == track.id) {
            if (positionMs != null || kotlin.math.abs(now.progressMs - from) > 5000) api.seek(from)
        } else {
            val i = queue.indexOfFirst { it.id == track.id }
            val uris = if (i >= 0) queue.drop(i).take(100).map { it.uri } else listOf(track.uri)
            api.play(device, uris = uris, offset = 0, positionMs = from)
            if (!play) { delay(400); api.pause() }
        }
    }

    /** librespot came back after dying: start it and pick the music up where it stopped. */
    fun recoverLocal() = scope.launch {
        val last = lastSpotify
        val resume = last?.isPlaying == true && last.deviceName == local.deviceName &&
            System.currentTimeMillis() - lastSpotifyAt < 30 * 60_000L &&
            !reverseOn.value && !fileOn.value
        val device = ensureLocal(transferPlayback = false) ?: return@launch
        if (!resume) return@launch
        runCatching { restoreOn(device) }
            .onFailure { say("could not resume: ${explain(it)}") }
        delay(400)
        refresh()
    }

    fun playTracks(tracks: List<Track>, index: Int, contextUri: String? = null) {
        queue = tracks
        queueIndex = index
        if (reverseOn.value) {
            scope.launch { tracks.getOrNull(index)?.let { launchReverse(it) } }
            return
        }
        val pick = tracks.getOrNull(index)
        if (LocalLibrary.isLocal(pick)) {
            scope.launch { playFile(pick!!) }
            return
        }
        if (fileOn.value) { files.stop(); fileOn.value = false }
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
        if (reverseOn.value) {
            val st = reverse.state.value ?: return@launch
            if (st.preparing) return@launch
            if (st.positionMs <= 0 && !st.playing) reverse.seek(st.durationMs)
            reverse.setPaused(st.playing)
            mirrorReverse()
            return@launch
        }
        if (fileOn.value) {
            val st = files.state.value
            if (st == null) playback.value?.track?.let { playFile(it) }
            else files.setPaused(st.playing)
            mirrorFile()
            return@launch
        }
        runCatching {
            val state = playback.value
            if (state?.isPlaying == true) {
                runCatching { api.pause() }.onFailure { if (!lostDevice(it)) throw it }
            } else {
                val device = ensureLocal(transferPlayback = false) ?: return@runCatching
                runCatching { api.resume(device) }.getOrElse { if (lostDevice(it)) restoreOn(device) else throw it }
            }
        }.onFailure { say("play/pause: ${explain(it)}") }
        delay(350)
        refresh()
    }

    fun next() {
        when {
            reverseOn.value -> scope.launch { reverseStep(+1) }
            fileOn.value -> scope.launch { fileStep(+1) }
            else -> call("next", recover = { restoreOn(it); delay(500); api.next() }) { api.next() }
        }
    }
    fun previous() {
        when {
            reverseOn.value -> scope.launch { reverseStep(-1) }
            fileOn.value -> scope.launch {
                // like every player: restart the track unless it just began
                val pos = files.state.value?.positionMs ?: 0
                if (pos > 3000) { files.seek(0); mirrorFile() } else fileStep(-1)
            }
            else -> call("prev", recover = { restoreOn(it, positionMs = 0) }) { api.previous() }
        }
    }
    fun seek(ms: Int) {
        when {
            reverseOn.value -> { reverse.seek(ms.toLong()); mirrorReverse() }
            fileOn.value -> { files.seek(ms.toLong()); mirrorFile() }
            else -> call("seek", recover = { restoreOn(it, positionMs = ms) }) { api.seek(ms) }
        }
    }

    // ---- local files -----------------------------------------------------

    private suspend fun playFile(track: Track, fromMs: Long = 0) {
        val st = playback.value
        if (!fileOn.value && st?.isPlaying == true && !LocalLibrary.isLocal(st.track)) runCatching { api.pause() }
        fileOn.value = true
        holdForeground()
        val i = queue.indexOfFirst { it.id == track.id }
        if (i >= 0) queueIndex = i else { queue = listOf(track); queueIndex = 0 }
        files.volume = 1f
        files.start(track, fromMs)
        mirrorFile()
    }

    private suspend fun fileStep(dir: Int, auto: Boolean = false) {
        val st = playback.value
        val repeat = st?.repeat ?: "off"
        if (auto && repeat == "track") { queue.getOrNull(queueIndex)?.let { playFile(it) }; return }
        var next = if (st?.shuffle == true && queue.size > 1) {
            var r: Int
            do { r = queue.indices.random() } while (r == queueIndex)
            r
        } else queueIndex + dir
        if (next !in queue.indices) {
            if (repeat == "context" && queue.isNotEmpty()) next = if (dir > 0) 0 else queue.lastIndex
            else {
                if (auto) { files.stop(); playback.value = playback.value?.copy(isPlaying = false, progressMs = 0) }
                return
            }
        }
        val track = queue.getOrNull(next) ?: return
        if (!LocalLibrary.isLocal(track)) return
        queueIndex = next
        playFile(track)
    }

    /** Shuffle and repeat for local files live here, Spotify keeps its own. */
    private var fileShuffle = false
    private var fileRepeat = "off"

    private fun mirrorFile() {
        val f = files.state.value ?: return
        playback.value = PlaybackState(
            f.track, f.playing, f.positionMs.toInt(), LOCAL_FILE_DEVICE, "this phone",
            fileShuffle, fileRepeat, 100,
        )
    }

    const val LOCAL_FILE_DEVICE = "slowva-local-file"

    // ---- reverse mode ----------------------------------------------------

    fun toggleReverse() = scope.launch { if (reverseOn.value) leaveReverse() else enterReverse() }

    private suspend fun enterReverse() {
        val st = playback.value
        val track = st?.track ?: run { say("start a track before using reverse"); return }
        if (LocalLibrary.isLocal(track)) {
            reverseOn.value = true
            val from = files.state.value?.positionMs
            files.stop()
            launchReverse(track, from)
            return
        }
        if (!SessionToken.available(app)) {
            say("reverse needs the phone paired once (Play here), so it can decode tracks")
            return
        }
        reverseOn.value = true
        holdForeground()
        if (st.isPlaying) runCatching { api.pause() }
        val i = queue.indexOfFirst { it.id == track.id }
        if (i >= 0) queueIndex = i else { queue = listOf(track); queueIndex = 0 }
        launchReverse(track)
    }

    private suspend fun launchReverse(track: Track, fromMs: Long? = null) {
        reverse.preparing(track)
        mirrorReverse()
        say("reverse: decoding ${track.name}…")
        if (LocalLibrary.isLocal(track)) {
            reverse.volume = 1f
            reverse.decodeLocal(track)
                .onSuccess { (file, sr) ->
                    if (!reverseOn.value) return
                    reverse.start(track, file, fromMs?.takeIf { it > 0 }, sr)
                    mirrorReverse()
                    say("reverse: end → beginning")
                }
                .onFailure {
                    say("reverse failed: ${it.message?.take(140)}")
                    leaveReverse(resume = false)
                }
            return
        }
        reverse.volume = (playback.value?.volumePercent ?: 100) / 100f
        reverse.decode(track)
            .onSuccess { file ->
                if (!reverseOn.value) return
                reverse.start(track, file)
                mirrorReverse()
                say("reverse: end → beginning")
            }
            .onFailure {
                say("reverse failed: ${it.message?.take(140)}")
                leaveReverse(resume = false)
            }
    }

    /** At a track's beginning reverse mode moves on to the next track, like spotc. */
    private suspend fun reverseStep(dir: Int) {
        if (!reverseOn.value) return
        val next = queueIndex + dir
        val track = queue.getOrNull(next)
        if (track == null) {
            if (dir > 0) { reverse.setPaused(true); mirrorReverse() } else reverse.seek(reverse.state.value?.durationMs ?: 0)
            return
        }
        queueIndex = next
        launchReverse(track)
    }

    private suspend fun leaveReverse(resume: Boolean = true) {
        val st = reverse.state.value
        reverse.stop()
        reverseOn.value = false
        if (st != null && LocalLibrary.isLocal(st.track)) {
            if (resume && !st.preparing) playFile(st.track, st.positionMs) else fileOn.value = false
            return
        }
        releaseForeground()
        if (resume && st != null && !st.preparing) {
            val device = ensureLocal(transferPlayback = false) ?: return
            runCatching {
                val window = queue.drop(queueIndex).take(100).map { it.uri }.ifEmpty { listOf(st.track.uri) }
                api.play(device, uris = window, offset = 0, positionMs = st.positionMs.toInt())
            }.onFailure { say("could not resume: ${it.message?.take(120)}") }
        }
        delay(400)
        refresh()
    }

    /** Shows the reversed track in the regular playback state the UI reads. */
    private fun mirrorReverse() {
        val r = reverse.state.value ?: return
        val base = playback.value
        playback.value = (base ?: PlaybackState(r.track, false, 0, null, null, false, "off", 100)).copy(
            track = r.track, isPlaying = r.playing, progressMs = r.positionMs.toInt(),
        )
    }
    fun setVolume(percent: Int) = call("volume") { api.setVolume(percent) }
    fun toggleShuffle() {
        if (fileOn.value) { fileShuffle = !fileShuffle; mirrorFile(); return }
        call("shuffle") { api.setShuffle(!(playback.value?.shuffle ?: false)) }
    }
    fun cycleRepeat() {
        if (fileOn.value) {
            fileRepeat = when (fileRepeat) { "off" -> "context"; "context" -> "track"; else -> "off" }
            mirrorFile(); return
        }
        spotifyRepeat()
    }
    private fun spotifyRepeat() = call("repeat") {
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
        if (fileOn.value && !reverseOn.value) { mirrorFile(); return }
        runCatching { api.state() }.onSuccess {
            if (it?.track != null && it.deviceId != null) {
                lastSpotify = it
                lastSpotifyAt = System.currentTimeMillis()
            } else if (it == null && !reverseOn.value) {
                // keep showing the last track so play/seek can bring it back
                playback.value = playback.value?.copy(isPlaying = false, deviceId = null)
                return@onSuccess
            }
            playback.value = it
            if (reverseOn.value) {
                reverse.volume = it?.volumePercent?.div(100f) ?: reverse.volume
                mirrorReverse()
            }
        }
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

    val width = MutableStateFlow(1.0f)
    val pan8d = MutableStateFlow(0.0f)
    val muffle = MutableStateFlow(0.0f)

    fun setSpace(width: Float = this.width.value, pan8d: Float = this.pan8d.value, muffle: Float = this.muffle.value) {
        this.width.value = width; this.pan8d.value = pan8d; this.muffle.value = muffle
        fx.width = width; fx.pan8d = pan8d; fx.muffle = muffle
        saveFx()
    }

    fun applyMood(mood: Mood) {
        setRetune(0)
        setSpeed(mood.rate)
        setReverb(mood.reverb)
        setBass(mood.bassDb, 120f)
        setSpace(mood.width, mood.pan8d, mood.muffle)
        val v = vinyl.value
        setVinyl(mood.vinyl?.apply(on = true) ?: v.copy(on = false))
    }

    val rain = MutableStateFlow(RainPreset.OFF)
    val rainLevel = MutableStateFlow(1f)
    /** what the rain chip turns back on */
    private var lastRain = RainPreset.RAIN

    fun setRain(preset: RainPreset, level: Float = rainLevel.value) {
        if (preset != RainPreset.OFF) lastRain = preset
        rain.value = preset; rainLevel.value = level
        fx.rain = preset; fx.rainLevel = level
        saveFx()
    }

    fun toggleRain() = setRain(if (rain.value == RainPreset.OFF) lastRain else RainPreset.OFF)

    val vinyl = MutableStateFlow(VinylSettings())

    fun setVinyl(value: VinylSettings) {
        vinyl.value = value
        fx.vinyl = value.on
        fx.vinylCrackle = value.crackle
        fx.vinylWarmth = value.warmth
        fx.vinylWow = value.wow
        fx.vinylSurface = value.surface
        saveFx()
    }

    fun resetFx() {
        setSpeed(1f)
        setReverb(0f)
        setRetune(0)
        setBass(0f, 120f)
        setSpace(1f, 0f, 0f)
        setVinyl(vinyl.value.copy(on = false))
        setRain(RainPreset.OFF)
    }

    private fun saveFx() {
        if (!::app.isInitialized) return
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("speed", speed.value)
            .putFloat("reverb", reverb.value)
            .putInt("retune", retuneIndex.value)
            .putFloat("bass_gain", bassGain.value)
            .putFloat("bass_freq", bassFreq.value)
            .putFloat("width", width.value)
            .putFloat("pan8d", pan8d.value)
            .putFloat("muffle", muffle.value)
            .putString("rain", rain.value.name)
            .putFloat("rain_level", rainLevel.value)
            .putBoolean("vinyl", vinyl.value.on)
            .putFloat("vinyl_crackle", vinyl.value.crackle)
            .putFloat("vinyl_warmth", vinyl.value.warmth)
            .putFloat("vinyl_wow", vinyl.value.wow)
            .putFloat("vinyl_surface", vinyl.value.surface)
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
        width.value = p.getFloat("width", 1f)
        pan8d.value = p.getFloat("pan8d", 0f)
        muffle.value = p.getFloat("muffle", 0f)
        fx.width = width.value; fx.pan8d = pan8d.value; fx.muffle = muffle.value
        rain.value = runCatching { RainPreset.valueOf(p.getString("rain", "OFF")!!) }.getOrDefault(RainPreset.OFF)
        rainLevel.value = p.getFloat("rain_level", 1f)
        fx.rain = rain.value; fx.rainLevel = rainLevel.value
        if (rain.value != RainPreset.OFF) lastRain = rain.value
        val d = VinylSettings()
        setVinyl(
            VinylSettings(
                on = p.getBoolean("vinyl", false),
                crackle = p.getFloat("vinyl_crackle", d.crackle),
                warmth = p.getFloat("vinyl_warmth", d.warmth),
                wow = p.getFloat("vinyl_wow", d.wow),
                surface = p.getFloat("vinyl_surface", d.surface),
            )
        )
    }

    private const val PREFS = "slowva-fx"
}
