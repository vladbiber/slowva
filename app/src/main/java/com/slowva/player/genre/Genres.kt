package com.slowva.player.genre

/**
 * The shared genre map: about 20 families used by radio tabs, sound presets and the wheel.
 * [tags] are the canonical genre tags, [aliases] catch messy file and web tags.
 */
enum class Genre(val label: String, val tags: List<String>, val aliases: List<String> = emptyList()) {
    POP("Pop", listOf("pop", "top 40", "hits", "dance pop", "europop", "k-pop", "pop rock"), listOf("top40", "chart")),
    ROCK("Rock", listOf("rock", "classic rock", "hard rock", "alternative rock", "alternative", "indie rock", "indie", "rock'n'roll"), listOf("alt rock", "grunge", "britpop")),
    METAL("Metal", listOf("metal", "heavy metal", "death metal", "black metal", "thrash metal", "metalcore", "power metal"), listOf("doom", "nu metal")),
    PUNK("Punk", listOf("punk", "punk rock", "hardcore", "pop punk", "post-punk"), listOf("emo", "ska punk")),
    HIPHOP("Hip-hop", listOf("hip hop", "hiphop", "hip-hop", "rap", "trap"), listOf("rap/hip hop", "drill", "grime")),
    RNB("R&B/Soul", listOf("rnb", "r&b", "soul", "funk", "motown", "neo soul"), listOf("rhythm and blues", "disco")),
    ELECTRONIC("Electronic", listOf("electronic", "electro", "edm", "dance", "trance", "synthwave", "electronica"), listOf("synth-pop", "synthpop", "eurodance")),
    HOUSE("House/Techno", listOf("house", "techno", "deep house", "minimal", "progressive house", "tech house"), listOf("club")),
    BASS("Bass music", listOf("drum and bass", "dnb", "dubstep", "jungle", "breakbeat", "bass"), listOf("drum & bass", "d&b", "garage", "uk garage")),
    JAZZ("Jazz", listOf("jazz", "smooth jazz", "swing", "bebop", "jazz fusion", "big band"), listOf("bossa nova")),
    BLUES("Blues", listOf("blues", "blues rock", "delta blues"), emptyList()),
    CLASSICAL("Classical", listOf("classical", "classic", "opera", "baroque", "orchestral", "symphony", "piano"), listOf("soundtrack", "score", "chamber")),
    FOLK("Acoustic/Folk", listOf("folk", "acoustic", "singer-songwriter", "americana", "celtic"), listOf("folk rock", "unplugged")),
    COUNTRY("Country", listOf("country", "bluegrass", "country music"), listOf("honky tonk")),
    REGGAE("Reggae", listOf("reggae", "dancehall", "ska", "dub", "roots reggae"), emptyList()),
    LATIN("Latin", listOf("latin", "salsa", "reggaeton", "bachata", "cumbia", "latin pop", "merengue"), listOf("tango", "flamenco")),
    LOFI("Lo-fi/Chill", listOf("lofi", "lo-fi", "chillout", "chill", "lounge", "downtempo", "chillhop"), listOf("chill out", "trip hop", "trip-hop")),
    AMBIENT("Ambient", listOf("ambient", "meditation", "relax", "new age", "relaxing", "sleep"), listOf("drone", "meditative")),
    SPOKEN("Spoken", listOf("news", "talk", "podcast", "spoken word", "comedy", "sports", "information"), listOf("audiobook", "talk radio")),
    ;

    companion object {
        private val index: Map<String, Genre> by lazy {
            val m = HashMap<String, Genre>()
            entries.forEach { g -> (g.tags + g.aliases + g.label).forEach { m[normalize(it)] = g } }
            m
        }

        /** "Rap/Hip Hop", "Hip-Hop" and "Trap" all give HIPHOP. null if nothing matches. */
        fun of(rawTag: String?): Genre? {
            if (rawTag.isNullOrBlank()) return null
            index[normalize(rawTag)]?.let { return it }
            // tags like "Rock; Pop" or "rap/hip hop": try the parts
            return rawTag.split(',', ';', '/', '|').firstNotNullOfOrNull { index[normalize(it)] }
        }

        /** The family most of [tags] point to, e.g. a station's tag list. */
        fun dominant(tags: List<String>): Genre? =
            tags.mapNotNull(::of).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

        private fun normalize(s: String) = s.trim().lowercase()
            .replace('_', ' ').replace(Regex("\\s+"), " ")
    }
}
