package com.slowva.player.player

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import com.slowva.player.data.Track
import com.slowva.player.listenbrainz.ListenBrainz
import com.slowva.player.player.fx.Fx
import com.slowva.player.player.fx.FxRange
import com.slowva.player.player.fx.RETUNE_PRESETS
import com.slowva.player.player.fx.RainPreset
import com.slowva.player.player.fx.VinylSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the player screens read: the current track and transport state. */
data class NowState(
    val track: Track?,
    val isPlaying: Boolean,
    val progressMs: Int,
    val durationMs: Long,
    val shuffle: Boolean,
    val repeat: String,
    val volumePercent: Int,
)

/** Sound settings, spectrum and transport shared by the UI, view model and audio processor. */
object PlayerHub {
    lateinit var app: Context
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val fx = Fx()
    val spectrum = MutableStateFlow(FloatArray(com.slowva.player.player.fx.SpectrumAnalyzer.BAR_COUNT))
    val playback = MutableStateFlow<NowState?>(null)
    val reverseOn = MutableStateFlow(false)
    /** true while a reversed copy of the track is being decoded */
    val reversePreparing = MutableStateFlow(false)

    /** what the playback controller in the service publishes */
    val ui = MutableStateFlow(PlayerUiState())

    /** Only the current song id and play state, so song rows don't redraw on every position tick. */
    val nowPlaying: StateFlow<Pair<String?, Boolean>> = ui.map { it.currentTrackId to it.isPlaying }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null to false)

    /** session handle held while the app is open; all transport goes through the service */
    @Volatile var remote: MediaController? = null

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        loadFx()
        ListenBrainz.init(app)
        val counter = PlayCounter()
        val listens = PlayCounter(ListenBrainz::thresholdMs)
        var announced: String? = null
        scope.launch {
            playback.collect { s ->
                val t = s?.track ?: return@collect
                if (counter.update(t.id, s.progressMs.toLong(), s.durationMs, s.isPlaying)) {
                    (app as com.slowva.player.SlowvaApp).repository.recordPlay(t)
                }
                if (s.isPlaying && t.id != announced) { announced = t.id; ListenBrainz.nowPlaying(t) }
                if (listens.update(t.id, s.progressMs.toLong(), s.durationMs, s.isPlaying)) {
                    ListenBrainz.listened(t, System.currentTimeMillis() - s.progressMs)
                }
            }
        }
    }

    fun togglePlay() {
        val c = remote ?: return
        if (c.isPlaying) c.pause()
        else {
            when (c.playbackState) {
                Player.STATE_IDLE -> c.prepare()
                // After a track ends, play() alone does nothing; rewind first so the
                // single tap actually restarts playback.
                Player.STATE_ENDED -> c.seekTo(0)
            }
            c.play()
        }
    }
    fun next() = send(Cmd.NEXT)
    fun previous() = send(Cmd.PREVIOUS)
    fun seek(ms: Int) = send(Cmd.SEEK) { putLong(Cmd.ARG_MS, ms.toLong()) }
    fun setVolume(percent: Int) { remote?.volume = (percent / 100f).coerceIn(0f, 1f) }
    fun toggleShuffle() { remote?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        val c = remote ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun toggleReverse() = send(Cmd.TOGGLE_REVERSE)
    fun playQueue(ids: List<String>, startIndex: Int) = send(Cmd.PLAY_QUEUE) {
        putStringArray(Cmd.ARG_IDS, ids.toTypedArray()); putInt(Cmd.ARG_INDEX, startIndex)
    }
    fun addToQueue(id: String) = send(Cmd.ADD_TO_QUEUE) { putString(Cmd.ARG_ID, id) }
    fun playNext(id: String) = send(Cmd.ADD_TO_QUEUE) { putString(Cmd.ARG_ID, id); putBoolean(Cmd.ARG_NEXT, true) }
    fun playQueueIndex(index: Int) = send(Cmd.PLAY_INDEX) { putInt(Cmd.ARG_INDEX, index) }
    fun moveInQueue(from: Int, to: Int) = send(Cmd.MOVE_IN_QUEUE) { putInt(Cmd.ARG_INDEX, from); putInt(Cmd.ARG_TO, to) }
    fun removeFromQueue(index: Int) = send(Cmd.REMOVE_FROM_QUEUE) { putInt(Cmd.ARG_INDEX, index) }

    private fun send(action: String, args: Bundle.() -> Unit = {}) {
        remote?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle().apply(args))
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

    /** 0 normal .. 1 widest effect sliders; lowering it pulls settings back inside. */
    val fxRange = MutableStateFlow(0f)

    fun setFxRange(value: Float) {
        val t = value.coerceIn(0f, 1f)
        fxRange.value = t
        rate.value.let { r -> FxRange.slow(t).let { if (r !in it) setRate(r.coerceIn(it)) } }
        if (reverb.value > FxRange.reverb(t).endInclusive) setReverb(FxRange.reverb(t).endInclusive)
        if (bassGain.value > FxRange.bass(t).endInclusive) setBass(FxRange.bass(t).endInclusive, bassFreq.value)
        if (width.value > FxRange.width(t).endInclusive) setSpace(width = FxRange.width(t).endInclusive)
        if (muffle.value > FxRange.muffle(t).endInclusive) setSpace(muffle = FxRange.muffle(t).endInclusive)
        if (rainLevel.value > FxRange.rain(t).endInclusive) setRain(rain.value, FxRange.rain(t).endInclusive)
        saveFx()
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
            .putFloat("fx_range", fxRange.value)
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
        fxRange.value = p.getFloat("fx_range", 0f)
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


/** Decides when a track counts as played: by default ~30 s of listening or half the track, whichever comes first. */
internal class PlayCounter(private val threshold: (Long) -> Long = ::thresholdMs) {
    private var id: String? = null
    private var lastMs = 0L
    private var heardMs = 0L
    private var counted = false

    /** True once per listen, on the update that crosses the threshold; seeking ahead is not listening. */
    fun update(trackId: String, positionMs: Long, durationMs: Long, playing: Boolean): Boolean {
        // a new track, or the same one started over (repeat one, previous)
        if (trackId != id || positionMs < RESTART_MS && lastMs - positionMs > RESTART_MS) {
            id = trackId; lastMs = positionMs; heardMs = 0; counted = false
        }
        val step = positionMs - lastMs
        lastMs = positionMs
        if (playing && step in 1..MAX_STEP_MS) heardMs += step
        if (counted || heardMs < threshold(durationMs)) return false
        counted = true
        return true
    }

    companion object {
        /** updates come every 250 ms; a bigger jump (fast speed aside) is a seek */
        private const val MAX_STEP_MS = 3_000L
        private const val RESTART_MS = 3_000L
        fun thresholdMs(durationMs: Long) = if (durationMs > 0) minOf(30_000L, durationMs / 2) else 30_000L
    }
}
