package com.slowva.player.ui.theme

/** A project or service the app builds on, for the About page. */
data class Credit(val name: String, val url: String, val license: String, val note: String)

object Credits {
    val entries: List<Credit> = listOf(
        Credit("Levyra", "https://github.com/LUC4N3X/Levyra-deepsound", "GPL-3.0", "Material 3 Expressive shape style and the song radio mixer"),
        Credit("LRCLIB", "https://lrclib.net", "Free API", "Synced lyrics"),
        Credit("Last.fm", "https://www.last.fm", "Last.fm API terms", "Genres, artists and charts"),
        Credit("MusicBrainz", "https://musicbrainz.org", "CC0 core data", "Album info"),
        Credit("ListenBrainz", "https://listenbrainz.org", "CC0 listen data", "Scrobbling"),
        Credit("Spun", "https://github.com/yappologistic/Spun", "Design inspiration", "The first player looks; no Spun code or assets"),
    )
}
