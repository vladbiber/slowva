package com.slowva.player.recommend

import com.slowva.player.data.Track

/** A song as the recommenders see it; [id] is the track id, [uri] what it plays from. */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val thumbnail: String? = null,
    val uri: String = "",
) {
    fun toTrack() = Track(id, title, artist, album ?: "", uri, thumbnail, durationMs, isDemo = false)
}

fun Track.toSong() = Song(id, title, artist, album.ifEmpty { null }, durationMs, coverUri, uri)

/**
 * An online catalog: finds songs known only by name and the song radio of a song.
 * None is set until an online source registers one in [SongRadio.provider].
 */
interface SongProvider {
    /** The online song for [artist] and [title], only when the match is confident; null otherwise. */
    suspend fun find(artist: String?, title: String, durationMs: Long = 0L): Track?

    /** Songs like [seed], best first. */
    suspend fun radio(seed: Track): List<Track>
}
