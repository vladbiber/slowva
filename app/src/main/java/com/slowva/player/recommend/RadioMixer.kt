package com.slowva.player.recommend

import java.text.Normalizer

/**
 * Cleans a song radio the way Levyra's RadioMixer does: no seeds or repeats, one version of each
 * song, at most [MAX_RUN] songs in a row by the same artist, songs not played yet first.
 */
object RadioMixer {
    const val MAX_RUN = 2

    /** [artistKey]s left out of every cleaned list: artists rated 2 stars or less. */
    @Volatile var excluded: Set<String> = emptySet()

    private val VERSION = Regex(
        "\\b(live|remix(ed)?|sped up|speed up|slowed|reverb|nightcore|cover|karaoke|instrumental|acoustic|8d|lyrics?|lyric video)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * "Song (Live)", "Song - Sped Up", "Song slowed + reverb" and the video title "Artist - Song
     * (Official Video)" all give "song".
     */
    fun work(title: String): String {
        val parts = title.split(" - ", limit = 2)
        // "Artist - Song" keeps the right side, "Song - Live" the left
        val right = parts.getOrNull(1)?.let(::bare)
        return if (right.isNullOrEmpty()) bare(parts[0]) else right
    }

    private fun bare(s: String) = core(s).replace(VERSION, " ").replace(Regex("\\s+"), " ").trim()

    /** Title without "feat.", "Remastered" and anything in brackets. */
    fun core(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        .replace(Regex("[(\\[{][^)\\]}]*[)\\]}]"), " ")
        .replace(Regex("\\b(feat|ft|featuring)\\b.*$"), " ")
        .replace(Regex("\\b(remaster(ed)?( \\d{4})?|\\d{4} remaster(ed)?|radio edit|official audio)\\b"), " ")
        .replace(Regex(" - .*$"), " ")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun isVersion(title: String) = VERSION.containsMatchIn(title)

    /** First credited artist as written: "A feat. B", "A & B" and "A, B" all give "A". */
    fun leadArtist(artist: String): String =
        artist.split(Regex(",|&| x | feat\\.? | ft\\.? | and ", RegexOption.IGNORE_CASE)).first().trim().removeSuffix(" - Topic").trim()

    /** [leadArtist], lower case, for matching. */
    fun artistKey(artist: String): String = leadArtist(artist).lowercase().removeSuffix(" - topic").trim()

    /** Same title and artist, or the same title where one of them is a cover, live, remix... */
    fun sameWork(a: Song, b: Song): Boolean {
        val w = work(a.title)
        if (w.isEmpty() || w != work(b.title)) return false
        return artistKey(a.artist) == artistKey(b.artist) || isVersion(a.title) || isVersion(b.title)
    }

    /** Drops [known] songs (seeds, songs already in the list), their other versions and [excluded] artists, then orders the rest. */
    fun clean(known: List<Song>, songs: List<Song>, played: Set<String>, excluded: Set<String> = this.excluded): List<Song> {
        val ids = known.mapTo(HashSet()) { it.id }
        val kept = ArrayList<Song>()
        for (s in songs) {
            if (!ids.add(s.id)) continue
            if (excluded.isNotEmpty() && artistKey(s.artist) in excluded) continue
            if (known.any { sameWork(it, s) } || kept.any { sameWork(it, s) }) continue
            kept += s
        }
        val (fresh, heard) = kept.partition { it.id !in played }
        return spread(fresh + heard)
    }

    /** Takes one song from each list in turn, then cleans the result. */
    fun mix(lists: List<List<Song>>, played: Set<String>): List<Song> {
        val out = ArrayList<Song>()
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { l -> l.getOrNull(i)?.let(out::add) }
        return clean(emptyList(), out, played)
    }

    /** Keeps the order but pulls a different artist forward when the same one would play [MAX_RUN] + 1 times running. */
    fun spread(songs: List<Song>): List<Song> {
        val pool = songs.toMutableList()
        val out = ArrayList<Song>(songs.size)
        while (pool.isNotEmpty()) {
            val run = out.takeLast(MAX_RUN).map { artistKey(it.artist) }
            val full = run.size == MAX_RUN && run.distinct().size == 1
            val i = if (full) pool.indexOfFirst { artistKey(it.artist) != run[0] } else 0
            out += pool.removeAt(if (i < 0) 0 else i)
        }
        return out
    }
}
