package com.slowva.player.player.sound

import android.content.Context
import com.slowva.player.data.db.DevicePresetEntity
import com.slowva.player.data.db.SoundDao
import com.slowva.player.data.db.TrackLoudness
import com.slowva.player.player.PlayerHub
import com.slowva.player.player.fx.CrossfeedPreset
import com.slowva.player.player.fx.EqBand
import com.slowva.player.player.fx.EqSpec
import com.slowva.player.player.fx.FilterType
import com.slowva.player.player.fx.Layers
import com.slowva.player.player.fx.RainPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

enum class RgMode(val label: String) { OFF("Off"), TRACK("Track"), ALBUM("Album") }

enum class EqMode(val label: String) { GRAPHIC("10-band"), PARAMETRIC("Parametric") }

/** The one-tap hi-fi starting points. */
enum class SoundPreset(val label: String) { STUDIO_FLAT("Studio flat"), HEADPHONES("Headphones"), LATE_NIGHT("Late night"), TAPE_DECK("Tape deck"), TUBE_AMP("Tube amp") }

data class HifiState(
    val rgMode: RgMode = RgMode.OFF,
    val rgPreampDb: Float = 0f,
    val rgPreventClip: Boolean = true,
    val loudness: Boolean = false,
    val tape: Float = 0f,
    val tube: Float = 0f,
    val night: Boolean = false,
    val nightThresholdDb: Float = -30f,
    val nightMakeupDb: Float = 6f,
    val balance: Float = 0f,
    val mono: Boolean = false,
    val swap: Boolean = false,
    val bitPerfect: Boolean = false,
)

data class EqState(
    val on: Boolean = false,
    val mode: EqMode = EqMode.GRAPHIC,
    val graphic: List<Float> = List(10) { 0f },
    val bands: List<EqBand> = emptyList(),
)

/** Correction for the current output device; saved per device key. */
data class DevicePreset(
    val eqOn: Boolean = false,
    val bands: List<EqBand> = emptyList(),
    val preampDb: Float = 0f,
    val eqName: String? = null,
    val crossfeed: CrossfeedPreset? = null,
)

/**
 * Settings for the device, genre and EQ layers and the hi-fi stages. Every change rebuilds
 * the audio thread's snapshot in [push]; layers that did not change keep their objects.
 */
object SoundSettings {
    val hifi = MutableStateFlow(HifiState())
    val eq = MutableStateFlow(EqState())
    val device = MutableStateFlow(DevicePreset())
    val genreKey = MutableStateFlow(GenrePreset.BALANCED)
    val genreAuto = MutableStateFlow(false)
    val presets = MutableStateFlow<List<GenrePreset>>(emptyList())
    /** the current track's loudness row, null when unknown */
    val loudnessRow = MutableStateFlow<TrackLoudness?>(null)

    private lateinit var app: Context
    private var dao: SoundDao? = null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val devicePresets = HashMap<String, DevicePreset>()
    private val userPresets = HashMap<String, GenrePreset>()
    private var volume = 1f

    private var deviceSpec: EqSpec? = null
    private var genreSpec: EqSpec? = null
    private var genreBuilt: GenrePreset? = null
    private var userSpec: EqSpec? = null
    private var loudSpec: EqSpec? = null
    private var loudBuilt = Float.NaN

    fun init(context: Context, soundDao: SoundDao) {
        if (::app.isInitialized) return
        app = context.applicationContext
        dao = soundDao
        GenrePresets.load(app)
        load()
        presets.value = GenrePresets.merged(userPresets)
        push()
        io.launch {
            soundDao.genrePresets().mapNotNull(GenrePresets::fromEntity).forEach { userPresets[it.key] = it }
            soundDao.devicePresets().forEach { e ->
                devicePresets[e.deviceKey] = DevicePreset(e.eqOn, AutoEq.decode(e.filters), e.preampDb, e.eqName,
                    e.crossfeed?.let { runCatching { CrossfeedPreset.valueOf(it) }.getOrNull() })
            }
            presets.value = GenrePresets.merged(userPresets)
            onDeviceChanged(OutputDevices.current.value)
        }
    }

    fun preset(key: String): GenrePreset =
        userPresets[key] ?: GenrePresets.bundled(key) ?: GenrePreset(key, GenrePreset.labelOf(key), List(10) { 0f })

    // ---- device layer ----------------------------------------------------

    fun onDeviceChanged(dev: OutputDevice?) {
        device.value = dev?.let { devicePresets[it.key] } ?: DevicePreset()
        push()
    }

    fun setDevice(transform: (DevicePreset) -> DevicePreset) {
        val dev = OutputDevices.current.value
        val p = transform(device.value)
        device.value = p
        push()
        if (dev == null) return
        devicePresets[dev.key] = p
        val d = dao ?: return
        io.launch {
            d.saveDevicePreset(
                DevicePresetEntity(dev.key, dev.name, dev.type, p.eqOn, AutoEq.encode(p.bands), p.preampDb, p.eqName, p.crossfeed?.name, System.currentTimeMillis())
            )
        }
    }

    // ---- genre layer -----------------------------------------------------

    /** Applies a genre preset; [fadeMs] about 1000 for the automatic switch at song start. */
    fun applyGenre(key: String, fadeMs: Int = 400) {
        genreKey.value = key
        genreBuilt = null
        pendingGenreFade = fadeMs
        push()
        save()
    }

    private var pendingGenreFade = 400

    fun saveGenreCopy(p: GenrePreset) {
        val copy = p.copy(edited = true, bands = p.bands.map { it.coerceIn(-12f, 12f) })
        userPresets[p.key] = copy
        presets.value = GenrePresets.merged(userPresets)
        if (genreKey.value == p.key) applyGenre(p.key, 120)
        val d = dao ?: return
        io.launch { d.saveGenrePreset(copy.toEntity(System.currentTimeMillis())) }
    }

    fun resetGenre(key: String) {
        userPresets.remove(key)
        presets.value = GenrePresets.merged(userPresets)
        if (genreKey.value == key) applyGenre(key, 120)
        val d = dao ?: return
        io.launch { d.deleteGenrePreset(key) }
    }

    fun setGenreAuto(on: Boolean) { genreAuto.value = on; save() }

    // ---- user EQ, hi-fi --------------------------------------------------

    fun setEq(transform: (EqState) -> EqState) {
        eq.value = transform(eq.value)
        userSpec = null
        push(); save()
    }

    fun setHifi(transform: (HifiState) -> HifiState) {
        val before = hifi.value
        var next = transform(before)
        if (next.bitPerfect && !before.bitPerfect) {
            val why = OutputDevices.setBitPerfect(true, SignalPath.decoderOut.value?.sampleRate)
            if (why != null) { bitPerfectError.value = why; next = next.copy(bitPerfect = false) } else bitPerfectError.value = null
        } else if (!next.bitPerfect && before.bitPerfect) OutputDevices.setBitPerfect(false, null)
        hifi.value = next
        push(); save()
    }

    val bitPerfectError = MutableStateFlow<String?>(null)

    fun setVolume(v: Float) {
        if (abs(v - volume) < 0.01f) return
        volume = v
        push()
    }

    fun setLoudnessRow(row: TrackLoudness?) { loudnessRow.value = row; push() }

    fun applyPreset(p: SoundPreset) {
        when (p) {
            SoundPreset.STUDIO_FLAT -> {
                PlayerHub.resetFx()
                hifi.value = hifi.value.copy(rgMode = RgMode.OFF, loudness = false, tape = 0f, tube = 0f, night = false, balance = 0f, mono = false, swap = false)
                eq.value = eq.value.copy(on = false)
                userSpec = null
                applyGenre(GenrePreset.BALANCED, 200)
                setDevice { it.copy(eqOn = false, crossfeed = null) }
            }
            SoundPreset.HEADPHONES -> setDevice { it.copy(eqOn = it.bands.isNotEmpty(), crossfeed = it.crossfeed ?: CrossfeedPreset.DEFAULT) }
            SoundPreset.LATE_NIGHT -> setHifi { it.copy(night = true, loudness = true, bitPerfect = false) }
            SoundPreset.TAPE_DECK -> setHifi { it.copy(tape = 0.6f, tube = 0f, bitPerfect = false) }
            SoundPreset.TUBE_AMP -> setHifi { it.copy(tube = 0.5f, tape = 0f, bitPerfect = false) }
        }
        push(); save()
    }

    /** Names of every stage that changes the sound right now; empty means true bypass. */
    fun activeStages(): List<String> = buildList {
        val h = hifi.value
        val d = device.value
        if (d.eqOn && d.bands.isNotEmpty()) add("Device EQ")
        if (d.crossfeed != null) add("Crossfeed")
        if (genreSpec != null) add("Genre ${preset(genreKey.value).label}")
        if (userSpec != null) add("EQ")
        if (loudSpec != null) add("Loudness")
        if (abs(PlayerHub.rate.value - 1f) > 0.001f) add("Speed")
        if (PlayerHub.reverb.value > 0.001f) add("Reverb")
        if (PlayerHub.bassGain.value > 0.01f) add("Bass")
        if (abs(PlayerHub.width.value - 1f) > 0.002f || PlayerHub.pan8d.value > 0.002f || PlayerHub.muffle.value > 0.001f) add("Space")
        if (PlayerHub.vinyl.value.on) add("Vinyl")
        if (PlayerHub.rain.value != RainPreset.OFF) add("Rain")
        if (PlayerHub.reverseOn.value) add("Reverse")
        if (PlayerHub.fx.hifi.tape > 0.001f) add("Tape")
        if (h.tube > 0.001f) add("Tube")
        if (PlayerHub.fx.hifi.night) add("Night mode")
        if (abs(PlayerHub.fx.hifi.gainDb) >= 0.01f) add("ReplayGain")
        if (abs(h.balance) > 0.001f || h.mono || h.swap) add("Balance")
    }

    /** Turns bit-perfect off as soon as any stage would change the samples. */
    fun checkBitPerfect() {
        if (hifi.value.bitPerfect && activeStages().isNotEmpty()) setHifi { it.copy(bitPerfect = false) }
    }

    // ---- snapshot for the audio thread -----------------------------------

    @Synchronized
    fun push() {
        val h = hifi.value
        val d = device.value
        val gp = preset(genreKey.value)
        val dSpec = if (d.eqOn && d.bands.isNotEmpty()) EqSpec(d.bands, 80) else null
        if (dSpec?.bands != deviceSpec?.bands) deviceSpec = dSpec
        if (genreBuilt != gp) {
            genreBuilt = gp
            genreSpec = gp.spec(pendingGenreFade)
            pendingGenreFade = 400
        }
        if (userSpec == null) {
            val e = eq.value
            userSpec = when {
                !e.on -> null
                e.mode == EqMode.GRAPHIC -> EqSpec.graphic(e.graphic.toFloatArray()).takeIf { !it.isFlat }
                else -> e.bands.takeIf { it.isNotEmpty() }?.let { EqSpec(it.take(10), 60) }
            }
        }
        val loudOn = h.loudness || gp.loudness
        val att = if (loudOn) (-20f * log10(volume.coerceIn(0.01f, 1f))) else 0f
        if (att != loudBuilt) {
            loudBuilt = att
            loudSpec = if (att < 0.5f) null else EqSpec(
                listOf(
                    EqBand(FilterType.LOW_SHELF, 100f, min(12f, att * 0.33f), 0.707f),
                    EqBand(FilterType.HIGH_SHELF, 9000f, min(5f, att * 0.12f), 0.707f),
                ),
                250,
            )
        }
        val headroom = EqSpec.peakDb(deviceSpec, genreSpec, userSpec, loudSpec)
        val x = PlayerHub.fx.hifi
        x.layers = Layers(deviceSpec, d.crossfeed, genreSpec, userSpec, loudSpec, headroom)
        x.tape = max(h.tape, gp.tape)
        x.tube = h.tube
        if (h.night) {
            x.night = true; x.nightThresholdDb = h.nightThresholdDb; x.nightRatio = 3f; x.nightMakeupDb = h.nightMakeupDb
        } else {
            x.night = gp.compress > 0f; x.nightThresholdDb = -24f; x.nightRatio = 1f + 2f * gp.compress; x.nightMakeupDb = 4f * gp.compress
        }
        x.gainDb = rgDb(h, loudnessRow.value)
        x.balance = h.balance; x.mono = h.mono; x.swap = h.swap
    }

    private fun rgDb(h: HifiState, row: TrackLoudness?): Float {
        if (h.rgMode == RgMode.OFF || row == null) return 0f
        val album = h.rgMode == RgMode.ALBUM
        val g = (if (album) row.albumGainDb ?: row.trackGainDb else row.trackGainDb ?: row.albumGainDb) ?: return 0f
        var db = g + h.rgPreampDb
        val peak = (if (album) row.albumPeak ?: row.trackPeak else row.trackPeak) ?: 0f
        if (h.rgPreventClip && peak > 0f) db = min(db, -20f * log10(peak))
        return db.coerceIn(-30f, 18f)
    }

    // ---- persistence -----------------------------------------------------

    private fun save() {
        if (!::app.isInitialized) return
        val h = hifi.value; val e = eq.value
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("rg_mode", h.rgMode.name).putFloat("rg_preamp", h.rgPreampDb).putBoolean("rg_clip", h.rgPreventClip)
            .putBoolean("loudness", h.loudness).putFloat("tape", h.tape).putFloat("tube", h.tube)
            .putBoolean("night", h.night).putFloat("night_thr", h.nightThresholdDb).putFloat("night_makeup", h.nightMakeupDb)
            .putFloat("balance", h.balance).putBoolean("mono", h.mono).putBoolean("swap", h.swap)
            .putBoolean("eq_on", e.on).putString("eq_mode", e.mode.name)
            .putString("eq_graphic", e.graphic.joinToString(",")).putString("eq_bands", AutoEq.encode(e.bands))
            .putString("genre", genreKey.value).putBoolean("genre_auto", genreAuto.value)
            .apply()
    }

    private fun load() {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        hifi.value = HifiState(
            rgMode = runCatching { RgMode.valueOf(p.getString("rg_mode", "OFF")!!) }.getOrDefault(RgMode.OFF),
            rgPreampDb = p.getFloat("rg_preamp", 0f), rgPreventClip = p.getBoolean("rg_clip", true),
            loudness = p.getBoolean("loudness", false), tape = p.getFloat("tape", 0f), tube = p.getFloat("tube", 0f),
            night = p.getBoolean("night", false), nightThresholdDb = p.getFloat("night_thr", -30f), nightMakeupDb = p.getFloat("night_makeup", 6f),
            balance = p.getFloat("balance", 0f), mono = p.getBoolean("mono", false), swap = p.getBoolean("swap", false),
        )
        val graphic = p.getString("eq_graphic", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 10 }
        eq.value = EqState(
            on = p.getBoolean("eq_on", false),
            mode = runCatching { EqMode.valueOf(p.getString("eq_mode", "GRAPHIC")!!) }.getOrDefault(EqMode.GRAPHIC),
            graphic = graphic ?: List(10) { 0f },
            bands = AutoEq.decode(p.getString("eq_bands", "") ?: ""),
        )
        genreKey.value = p.getString("genre", GenrePreset.BALANCED) ?: GenrePreset.BALANCED
        genreAuto.value = p.getBoolean("genre_auto", false)
    }

    private const val PREFS = "slowva-sound"
}
