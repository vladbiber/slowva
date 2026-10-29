package com.slowva.player.lastfm

import com.slowva.player.data.MusicRepository
import com.slowva.player.data.Track
import com.slowva.player.genre.Genre
import com.slowva.player.data.db.SoundDao
import kotlinx.coroutines.flow.first

/** What the listener likes, from listening history, as Last.fm tags and artists. */
object Taste {
    /** Last.fm tags for the genre families in [raw], most frequent first. */
    fun genreTags(raw: List<String?>): List<String> =
        raw.mapNotNull { Genre.of(it) }.filter { it != Genre.SPOKEN }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key.tags.first() }

    /** [mine] first, then popular tags of other families, [n] in all. */
    fun genreRow(mine: List<String>, popular: List<String>, n: Int = 12): List<String> {
        val families = mine.mapNotNullTo(HashSet()) { Genre.of(it) }
        val out = mine.take(n).toMutableList()
        for (t in popular) {
            if (out.size >= n) break
            val g = Genre.of(t)
            if (t in out || (g != null && !families.add(g))) continue
            out += t
        }
        return out
    }

    /** Your own genres land three times as often as the rest. */
    fun weight(tag: String, mine: List<String>): Int {
        val g = Genre.of(tag)
        return if (tag in mine || (g != null && mine.any { Genre.of(it) == g })) 3 else 1
    }

    /** Artists of [plays], most played first, each with a cover from one of their songs. */
    fun artists(plays: List<Track>): List<LastFm.Artist> =
        plays.filter { it.artist.isNotBlank() && !it.artist.equals("Unknown artist", true) }
            .groupBy { it.artist.lowercase() }.values.sortedByDescending { it.size }
            .map { songs -> LastFm.Artist(songs.first().artist, songs.firstNotNullOfOrNull { it.coverUri }) }

    /** History: plays count once, most played songs twice more. */
    suspend fun plays(repo: MusicRepository): List<Track> {
        val most = repo.mostPlayed(30).first()
        return repo.recentlyPlayed(100).first() + most + most
    }

    /** Genre tags from what you played: the genre set for the song or its artist, else the saved one. */
    suspend fun myGenres(repo: MusicRepository, sound: SoundDao): List<String> {
        val genreOf = HashMap<String, String?>()
        val played = plays(repo).map { t ->
            if (t.id !in genreOf) genreOf[t.id] = sound.songGenre(t.id) ?: sound.artistGenre(t.artist) ?: sound.savedGenre(t.id)
            genreOf[t.id]
        }
        return genreTags(played)
    }
}
