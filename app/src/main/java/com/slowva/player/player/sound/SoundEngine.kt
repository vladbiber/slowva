package com.slowva.player.player.sound

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.slowva.player.SlowvaApp
import com.slowva.player.data.Track
import com.slowva.player.data.db.ArtistGenre
import com.slowva.player.data.db.SongGenreOverride
import com.slowva.player.data.db.SoundDao
import com.slowva.player.data.db.TrackLoudness
import com.slowva.player.genre.Genre
import com.slowva.player.player.NowState
import com.slowva.player.player.PlayerHub
import com.slowva.player.player.fx.R128Meter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The suggested genre preset for what plays now, and why. [autoFrom] is set after an automatic switch. */
data class GenreSuggestion(val itemId: String, val key: String, val reason: String, val autoFrom: String? = null)

/** What plays now. */
private data class Item(val id: String, val track: Track)

/**
 * Lives as long as playback: follows the output device, the volume and the current item,
 * suggests (and with auto-apply sets) the genre preset, and finds each track's ReplayGain.
 */
object SoundEngine {
    val suggestion = MutableStateFlow<GenreSuggestion?>(null)
    val rgStatus = MutableStateFlow<String?>(null)

    private lateinit var app: Context
    private lateinit var dao: SoundDao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: Item? = null

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        PlayerHub.init(app)
        dao = (app as SlowvaApp).database.sound()
        OutputDevices.init(app)
        SoundSettings.init(app, dao)
        scope.launch { OutputDevices.current.drop(1).collect { SoundSettings.onDeviceChanged(it) } }
        scope.launch {
            PlayerHub.playback.filterNotNull().map { it.volumePercent }.distinctUntilChanged()
                .collect { SoundSettings.setVolume(it / 100f) }
        }
        scope.launch {
            PlayerHub.playback.map(::itemOf).distinctUntilChangedBy { it?.id }.collectLatest { item ->
                current = item
                if (item == null) return@collectLatest
                coroutineScope {
                    launch { suggest(item, fresh = true) }
                    launch { replayGain(item) }
                }
            }
        }
        scope.launch {
            SoundSettings.hifi.map { it.rgMode }.distinctUntilChanged().drop(1).collectLatest {
                current?.let { replayGain(it) }
            }
        }
        scope.launch {
            SignalPath.decoderOut.filterNotNull().map { it.sampleRate }.distinctUntilChanged().collect { rate ->
                if (!SoundSettings.hifi.value.bitPerfect) return@collect
                OutputDevices.setBitPerfect(true, rate)?.let { why ->
                    SoundSettings.bitPerfectError.value = why
                    SoundSettings.setHifi { it.copy(bitPerfect = false) }
                }
            }
        }
        scope.launch {
            merge(
                PlayerHub.rate, PlayerHub.reverb, PlayerHub.bassGain, PlayerHub.width, PlayerHub.pan8d, PlayerHub.muffle,
                PlayerHub.vinyl, PlayerHub.rain, PlayerHub.reverseOn, SoundSettings.eq, SoundSettings.device, SoundSettings.genreKey,
            ).collect { SoundSettings.checkBitPerfect() }
        }
    }

    private fun itemOf(s: NowState?): Item? {
        val t = s?.track ?: return null
        return Item(t.id, t)
    }

    // ---- genre -----------------------------------------------------------

    private suspend fun suggest(item: Item, fresh: Boolean) {
        val (key, reason) = withContext(Dispatchers.IO) { detect(item) }
        val now = SoundSettings.genreKey.value
        if (fresh && SoundSettings.genreAuto.value && key != now) {
            SoundSettings.applyGenre(key, 1000)
            suggestion.value = GenreSuggestion(item.id, key, reason, autoFrom = now)
        } else {
            suggestion.value = GenreSuggestion(item.id, key, reason)
        }
    }

    /** First hit wins: song choice, artist choice, file tag, saved genre, Balanced. */
    private suspend fun detect(item: Item): Pair<String, String> {
        runCatching { dao.songGenre(item.id) }.getOrNull()?.takeIf(::valid)?.let { return it to "your choice for this song" }
        val artist = artistKey(item.track.artist)
        if (artist != null) runCatching { dao.artistGenre(artist) }.getOrNull()?.takeIf(::valid)?.let {
            return it to "you picked it for ${item.track.artist}"
        }
        fileGenre(item.track.uri)?.let { Genre.of(it) }?.let { return it.name to "from the song's tag" }
        runCatching { dao.savedGenre(item.id) }.getOrNull()?.let { Genre.of(it) }?.let { return it.name to "saved with the song" }
        return GenrePreset.BALANCED to "no genre found"
    }

    private fun valid(key: String) = key == GenrePreset.BALANCED || runCatching { Genre.valueOf(key) }.isSuccess

    private fun artistKey(artist: String): String? =
        artist.trim().lowercase().takeIf { it.isNotEmpty() && it != "unknown artist" && it != "<unknown>" && it != "unknown" }

    private fun fileGenre(uri: String): String? {
        if (!isLocal(uri)) return null
        val mmr = MediaMetadataRetriever()
        return try {
            if (uri.startsWith(ASSET)) app.assets.openFd(uri.removePrefix(ASSET)).use { mmr.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            else mmr.setDataSource(app, Uri.parse(uri))
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** The Apply button on the suggestion card. */
    fun applySuggestion() {
        val s = suggestion.value ?: return
        SoundSettings.applyGenre(s.key, 400)
    }

    fun undoAuto() {
        val s = suggestion.value ?: return
        val back = s.autoFrom ?: return
        SoundSettings.applyGenre(back, 400)
        suggestion.value = s.copy(autoFrom = null)
    }

    /** A tap on a preset. With auto-apply on it also becomes this song's choice and teaches the artist. */
    fun pickGenre(key: String) {
        SoundSettings.applyGenre(key, 400)
        val item = current ?: return
        if (!SoundSettings.genreAuto.value) return
        val now = System.currentTimeMillis()
        suggestion.value = GenreSuggestion(item.id, key, "your choice for this song")
        scope.launch(Dispatchers.IO) {
            runCatching {
                dao.setSongGenre(SongGenreOverride(item.id, key, now))
                artistKey(item.track.artist)?.let { dao.setArtistGenre(ArtistGenre(it, key, "user", now)) }
            }
        }
    }

    // ---- ReplayGain ------------------------------------------------------

    private suspend fun replayGain(item: Item) {
        if (SoundSettings.hifi.value.rgMode == RgMode.OFF) {
            rgStatus.value = null
            SoundSettings.setLoudnessRow(null)
            return
        }
        val id = item.id
        if (!isLocal(item.track.uri)) { rgStatus.value = "only for files on the phone"; SoundSettings.setLoudnessRow(null); return }
        val row = withContext(Dispatchers.IO) { runCatching { dao.loudness(id) }.getOrNull() }
            ?: withContext(Dispatchers.IO) { fromTags(id, item.track.uri) }
            ?: run {
                rgStatus.value = "measuring loudness…"
                withContext(Dispatchers.IO) { measure(id, item.track.uri) }
            }
        SoundSettings.setLoudnessRow(row.takeIf { it.source != "failed" })
        rgStatus.value = describe(row)
    }

    private suspend fun fromTags(id: String, uri: String): TrackLoudness? {
        val t = runCatching { ReplayGainTags.read(app, uri) }.getOrNull() ?: return null
        val row = TrackLoudness(id, t.trackGain, t.albumGain, t.trackPeak, t.albumPeak, null, "tag", System.currentTimeMillis())
        runCatching { dao.saveLoudness(row) }
        return row
    }

    private suspend fun measure(id: String, uri: String): TrackLoudness {
        val m = LoudnessScan.measure(app, uri)
        val row = if (m == null) TrackLoudness(id, null, null, null, null, null, "failed", System.currentTimeMillis())
        else TrackLoudness(id, (R128Meter.REFERENCE_LUFS - m.lufs).toFloat(), null, m.peak.takeIf { it > 0f }, null, m.lufs, "measured", System.currentTimeMillis())
        runCatching { dao.saveLoudness(row) }
        return row
    }

    private fun describe(r: TrackLoudness): String = when (r.source) {
        "failed" -> "could not measure this file"
        "measured" -> "%+.1f dB, measured %.1f LUFS".format(r.trackGainDb ?: 0f, r.lufs ?: 0f)
        else -> buildString {
            r.trackGainDb?.let { append("track %+.1f dB".format(it)) }
            r.albumGainDb?.let { if (isNotEmpty()) append(" · "); append("album %+.1f dB".format(it)) }
            append(" from tags")
        }
    }

    /** streams would go around the network gate, so only local files are read */
    private fun isLocal(uri: String) = uri.startsWith("content:") || uri.startsWith("file:") || uri.startsWith("/")

    private const val ASSET = "file:///android_asset/"
}
