package com.slowva.player.player

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.slowva.player.player.fx.Mood
import com.slowva.player.player.fx.Moods
import kotlinx.coroutines.flow.combine
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.slowva.player.data.MusicRepository
import com.slowva.player.data.PlaybackState
import com.slowva.player.data.Track
import com.slowva.player.recommend.UpNext
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class PlayerUiState(
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val volume: Float = 1f,
    val queueIds: List<String> = emptyList(),
    val currentIndex: Int = -1,
)

/** Custom session commands the app sends to [PlaybackController]. */
object Cmd {
    const val PLAY_QUEUE = "slowva.PLAY_QUEUE"
    const val ADD_TO_QUEUE = "slowva.ADD_TO_QUEUE"
    const val PLAY_INDEX = "slowva.PLAY_INDEX"
    const val NEXT = "slowva.NEXT"
    const val PREVIOUS = "slowva.PREVIOUS"
    const val SEEK = "slowva.SEEK"
    const val TOGGLE_REVERSE = "slowva.TOGGLE_REVERSE"
    const val MOVE_IN_QUEUE = "slowva.MOVE_IN_QUEUE"
    const val REMOVE_FROM_QUEUE = "slowva.REMOVE_FROM_QUEUE"
    val ALL = listOf(PLAY_QUEUE, ADD_TO_QUEUE, PLAY_INDEX, NEXT, PREVIOUS, SEEK, TOGGLE_REVERSE, MOVE_IN_QUEUE, REMOVE_FROM_QUEUE)
    /** safe for any controller, e.g. the system media controls and Bluetooth */
    val PUBLIC = listOf(NEXT, PREVIOUS)

    const val ARG_IDS = "ids"
    const val ARG_ID = "id"
    /** with ADD_TO_QUEUE: right after the current song instead of at the end */
    const val ARG_NEXT = "next"
    const val ARG_INDEX = "index"
    const val ARG_TO = "to"
    const val ARG_MS = "ms"
}

/** Queue, reverse mode and snapshot logic. Owned by [PlaybackService], so it works with no UI. */
class PlaybackController(
    private val context: Context,
    private val player: Player,
    private val repository: MusicRepository,
) {
    private val scope = MainScope()
    private val reverser = ReverseDecoder(context)
    /** the forward queue that reverse mode walks through, track by track */
    private var reverseQueue: List<Track> = emptyList()
    private var reverseIndex = 0
    /** the library songs a Recommended up next replaced, in play order; null while the library queue plays */
    private var libraryUpcoming: List<MediaItem>? = null
    private var mixJob: Job? = null
    private var mixFor: String? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED && PlayerHub.reverseOn.value) reverseStep(+1)
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = upNext()
    }

    init {
        player.addListener(listener)
        UpNext.init(context)
        scope.launch { UpNext.recommended.collect { upNext() } }
        scope.launch {
            if (repository.tracks.value.isEmpty()) repository.load(includeDevice = false)
            restoreSnapshot()
            sync()
        }
        // speed and pitch move together, like slowva's resampler
        Moods.init(context)
        scope.launch {
            combine(PlayerHub.rate, Moods.mood) { r, m -> r * m.pitch }
                .collect { player.playbackParameters = PlaybackParameters(it, it) }
        }
        // Good & Evil plays everything backwards; leaving it plays forwards again
        scope.launch {
            var was = Moods.mood.value
            Moods.mood.collect { m ->
                PlayerHub.fx.mood = m
                val reversed = PlayerHub.reverseOn.value
                if (m == Mood.GOOD_EVIL && !reversed && player.mediaItemCount > 0) enterReverse()
                else if (was == Mood.GOOD_EVIL && m != Mood.GOOD_EVIL && reversed) leaveReverse()
                was = m
            }
        }
        // a new song started forwards while Good & Evil is on: turn it around too
        scope.launch {
            PlayerHub.ui.map { it.currentTrackId }.distinctUntilChanged().collect {
                if (it != null && Moods.mood.value == Mood.GOOD_EVIL && !PlayerHub.reverseOn.value &&
                    !PlayerHub.reversePreparing.value) enterReverse()
            }
        }
        scope.launch {
            while (true) {
                if (player.isPlaying) {
                    PlayerHub.ui.value = PlayerHub.ui.value.copy(
                        positionMs = shownPosition(),
                        durationMs = player.duration.coerceAtLeast(0L),
                    )
                    publish()
                }
                delay(250)
            }
        }
    }

    /** Runs a custom command; false if the action or its arguments are unknown. */
    fun handle(action: String, args: Bundle): Boolean {
        when (action) {
            Cmd.PLAY_QUEUE -> {
                val ids = args.getStringArray(Cmd.ARG_IDS) ?: return false
                playQueue(ids.mapNotNull(repository::trackById), args.getInt(Cmd.ARG_INDEX))
            }
            Cmd.ADD_TO_QUEUE -> addToQueue(args.getString(Cmd.ARG_ID) ?: return false, args.getBoolean(Cmd.ARG_NEXT))
            Cmd.PLAY_INDEX -> playQueueIndex(args.getInt(Cmd.ARG_INDEX, -1))
            // the reversed copy is one file, so its queue can't be edited
            Cmd.MOVE_IN_QUEUE -> if (!PlayerHub.reverseOn.value) {
                val from = args.getInt(Cmd.ARG_INDEX, -1)
                val to = args.getInt(Cmd.ARG_TO, -1)
                if (from in 0 until player.mediaItemCount && to in 0 until player.mediaItemCount) player.moveMediaItem(from, to)
            }
            Cmd.REMOVE_FROM_QUEUE -> if (!PlayerHub.reverseOn.value) {
                val i = args.getInt(Cmd.ARG_INDEX, -1)
                if (i in 0 until player.mediaItemCount && i != player.currentMediaItemIndex) player.removeMediaItem(i)
            }
            Cmd.NEXT -> next()
            Cmd.PREVIOUS -> previous()
            Cmd.SEEK -> seekTo(args.getLong(Cmd.ARG_MS).coerceAtLeast(0L))
            Cmd.TOGGLE_REVERSE -> scope.launch { if (PlayerHub.reverseOn.value) leaveReverse() else enterReverse() }
            else -> return false
        }
        return true
    }

    private suspend fun restoreSnapshot() {
        val snap = repository.loadPlayback()
        player.shuffleModeEnabled = snap.shuffle
        player.repeatMode = snap.repeatMode
        player.volume = snap.volume
        val track = repository.trackById(snap.trackId)
        if (track != null && player.mediaItemCount == 0) {
            player.setMediaItem(track.toMediaItem(), snap.positionMs)
            player.prepare()
            player.playWhenReady = false
        }
    }

    private fun sync() {
        val rev = PlayerHub.reverseOn.value
        PlayerHub.ui.value = PlayerUiState(
            currentTrackId = player.currentMediaItem?.mediaId?.removePrefix(REV),
            isPlaying = player.isPlaying,
            positionMs = shownPosition(),
            durationMs = player.duration.coerceAtLeast(0L),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            volume = player.volume,
            queueIds = if (rev) reverseQueue.map { it.id } else (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId },
            currentIndex = if (rev) reverseIndex else player.currentMediaItemIndex,
        )
        publish()
        if (!rev) persistSnapshot()
    }

    /** In reverse mode the file runs backwards, so the shown position is mirrored. */
    private fun shownPosition(): Long {
        val pos = player.currentPosition.coerceAtLeast(0L)
        return if (PlayerHub.reverseOn.value && player.duration > 0) (player.duration - pos).coerceAtLeast(0L) else pos
    }

    private fun publish() {
        val u = PlayerHub.ui.value
        val track = repository.trackById(u.currentTrackId)
        PlayerHub.playback.value = if (track == null) null else NowState(
            track = track,
            isPlaying = u.isPlaying,
            progressMs = u.positionMs.toInt(),
            durationMs = if (u.durationMs > 0) u.durationMs else track.durationMs,
            shuffle = u.shuffle,
            repeat = when (u.repeatMode) {
                Player.REPEAT_MODE_ONE -> "track"
                Player.REPEAT_MODE_ALL -> "context"
                else -> "off"
            },
            volumePercent = (u.volume * 100).toInt(),
        )
    }

    private fun persistSnapshot() {
        repository.savePlayback(
            PlaybackState(
                trackId = player.currentMediaItem?.mediaId,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                volume = player.volume,
            )
        )
    }

    private fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        // a new queue: Recommended mixes from its first song again
        libraryUpcoming = null
        if (PlayerHub.reverseOn.value) {
            reverseQueue = tracks
            reverseIndex = startIndex.coerceIn(0, tracks.lastIndex)
            scope.launch { launchReverse(tracks[reverseIndex], 0L) }
            return
        }
        player.setMediaItems(tracks.map { it.toMediaItem() }, startIndex.coerceIn(0, tracks.lastIndex), 0L)
        player.prepare()
        player.play()
    }

    /** Appends a track to the end of the current play queue (or after the current song), starting one if nothing is loaded. */
    private fun addToQueue(trackId: String, next: Boolean = false) {
        val track = repository.trackById(trackId) ?: return
        if (player.mediaItemCount == 0) {
            libraryUpcoming = null
            player.setMediaItem(track.toMediaItem())
            player.prepare()
            player.play()
        } else if (next) {
            player.addMediaItem(player.currentMediaItemIndex + 1, track.toMediaItem())
        } else {
            player.addMediaItem(track.toMediaItem())
        }
        Toast.makeText(context, (if (next) "Plays next: " else "Added to queue: ") + track.title, Toast.LENGTH_SHORT).show()
    }

    /** Jumps to a specific song in the current play queue. */
    private fun playQueueIndex(index: Int) {
        if (PlayerHub.reverseOn.value) {
            val t = reverseQueue.getOrNull(index) ?: return
            reverseIndex = index
            scope.launch { launchReverse(t, 0L) }
            return
        }
        if (index < 0 || index >= player.mediaItemCount) return
        player.seekTo(index, 0L)
        player.play()
    }

    private fun next() {
        if (PlayerHub.reverseOn.value) reverseStep(+1) else player.seekToNext()
    }

    private fun previous() {
        if (PlayerHub.reverseOn.value) { reverseStep(-1); return }
        // After 5s of playback, the back arrow restarts the current track instead of skipping back.
        if (player.currentPosition > 5000) player.seekTo(0) else player.seekToPrevious()
    }

    private fun seekTo(ms: Long) {
        if (PlayerHub.reverseOn.value && player.duration > 0) player.seekTo((player.duration - ms).coerceAtLeast(0L))
        else player.seekTo(ms)
        PlayerHub.ui.value = PlayerHub.ui.value.copy(positionMs = ms)
    }

    private suspend fun enterReverse() {
        val id = player.currentMediaItem?.mediaId ?: return
        val track = repository.trackById(id) ?: return
        reverseQueue = (0 until player.mediaItemCount).mapNotNull { repository.trackById(player.getMediaItemAt(it).mediaId) }
            .ifEmpty { listOf(track) }
        reverseIndex = reverseQueue.indexOfFirst { it.id == id }.coerceAtLeast(0)
        val fwd = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration.takeIf { it > 0 } ?: track.durationMs
        PlayerHub.reverseOn.value = true
        launchReverse(track, (dur - fwd).coerceAtLeast(0L))
    }

    /** Starts [track] reversed, [revStartMs] into the reversed file. */
    private suspend fun launchReverse(track: Track, revStartMs: Long) {
        PlayerHub.reversePreparing.value = true
        player.pause()
        val file = reverser.reversed(track).getOrElse {
            PlayerHub.reversePreparing.value = false
            PlayerHub.reverseOn.value = false
            // Good & Evil turns songs around on its own: one that can't be reversed just keeps playing forwards
            if (Moods.mood.value == Mood.GOOD_EVIL) player.play()
            else Toast.makeText(context, "Reverse failed: ${it.message}", Toast.LENGTH_LONG).show()
            return
        }
        PlayerHub.reversePreparing.value = false
        if (!PlayerHub.reverseOn.value) return
        val item = track.toMediaItem().buildUpon()
            .setMediaId(REV + track.id)
            .setUri(Uri.fromFile(file))
            .build()
        player.setMediaItem(item, revStartMs)
        player.prepare()
        player.play()
    }

    private fun reverseStep(dir: Int) {
        val next = reverseIndex + dir
        val track = reverseQueue.getOrNull(next) ?: run {
            if (dir < 0) player.seekTo(0)
            return
        }
        reverseIndex = next
        scope.launch { launchReverse(track, 0L) }
    }

    private fun leaveReverse() {
        val shown = shownPosition()
        val wasPlaying = player.isPlaying
        PlayerHub.reverseOn.value = false
        if (reverseQueue.isEmpty()) return
        player.setMediaItems(reverseQueue.map { it.toMediaItem() }, reverseIndex.coerceIn(0, reverseQueue.lastIndex), shown)
        player.prepare()
        if (wasPlaying) player.play()
    }

    /**
     * Keeps Up next on what its switch says: the queue the listener started, or the mix of the song playing
     * now (fetched once on the switch, then topped up when fewer than [LOW] songs are left).
     */
    private fun upNext() {
        // reverse walks its own copy of the queue
        if (PlayerHub.reverseOn.value) return
        if (!UpNext.recommended.value) {
            mixJob?.cancel()
            UpNext.loading.value = false
            val saved = libraryUpcoming ?: return
            libraryUpcoming = null
            replaceUpcoming(saved)
            return
        }
        val seed = repository.trackById(player.currentMediaItem?.mediaId) ?: return
        val left = player.mediaItemCount - 1 - player.currentMediaItemIndex
        val first = libraryUpcoming == null
        if (!first && left >= LOW) return
        if (mixJob?.isActive == true && mixFor == seed.id) return
        mixJob?.cancel()
        mixFor = seed.id
        mixJob = scope.launch {
            UpNext.loading.value = first
            val queued = (0 until player.mediaItemCount).mapNotNull { repository.trackById(player.getMediaItemAt(it).mediaId) }
            val songs = try {
                UpNext.fetch(context, repository, seed, queued)
            } catch (e: UpNext.Unavailable) {
                // a failed top-up keeps what is queued; a failed switch falls back to Library
                if (first) UpNext.fail(e.message.orEmpty())
                return@launch
            } finally {
                if (mixFor == seed.id) UpNext.loading.value = false
            }
            if (player.currentMediaItem?.mediaId != seed.id || !UpNext.recommended.value || PlayerHub.reverseOn.value) return@launch
            val items = songs.map { it.toMediaItem() }
            if (libraryUpcoming != null) { player.addMediaItems(items); return@launch }
            // saved in the order they would have played, so a shuffled queue comes back as it was
            libraryUpcoming = generateSequence(player.currentMediaItemIndex) {
                player.currentTimeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled).takeIf { i -> i != C.INDEX_UNSET }
            }.drop(1).map(player::getMediaItemAt).toList()
            if (player.shuffleModeEnabled) {
                // shuffled songs sit on both sides of the current one: keep only it, so the mix plays in order right after it
                // shortcut: shuffle stays off after Library comes back (its songs return in their shuffled order)
                player.shuffleModeEnabled = false
                player.removeMediaItems(0, player.currentMediaItemIndex)
            }
            replaceUpcoming(items)
        }
    }

    private fun replaceUpcoming(items: List<MediaItem>) =
        player.replaceMediaItems(player.currentMediaItemIndex + 1, player.mediaItemCount, items)

    fun release() {
        if (!PlayerHub.reverseOn.value) persistSnapshot()
        player.removeListener(listener)
        scope.cancel()
    }
}

private const val REV = "rev:"
/** songs left in a Recommended up next before it is topped up */
private const val LOW = 5
