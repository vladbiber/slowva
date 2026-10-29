package com.slowva.player.spotify

import com.slowva.player.data.Track
import org.json.JSONArray
import org.json.JSONObject

data class SpArtist(val id: String, val name: String, val art: String? = null, val genres: List<String> = emptyList())

data class SpTrack(
    val id: String,
    val name: String,
    val artists: List<SpArtist>,
    val album: String,
    val durationMs: Long,
    val art: String?,
    val playable: Boolean = true,
) {
    val artist: String get() = artists.joinToString(", ") { it.name }

    fun toTrack() = Track(SpotifyIds.trackId(id), name, artist, album, SpotifyIds.streamUri(id, durationMs), art, durationMs, isDemo = false)
}

data class SpAlbum(val id: String, val name: String, val artist: String, val art: String?, val tracks: Int = 0)

data class SpPlaylist(val id: String, val name: String, val owner: String, val art: String?, val tracks: Int = 0)

/** Search results, one list per kind. */
data class SpResults(
    val tracks: List<SpTrack> = emptyList(),
    val albums: List<SpAlbum> = emptyList(),
    val playlists: List<SpPlaylist> = emptyList(),
    val artists: List<SpArtist> = emptyList(),
) {
    val isEmpty get() = tracks.isEmpty() && albums.isEmpty() && playlists.isEmpty() && artists.isEmpty()
}

/** Track ids and stream uris of Spotify songs inside Slowva. */
object SpotifyIds {
    const val SCHEME = "spstream"
    private const val PREFIX = "sp:"

    fun trackId(spotifyId: String) = PREFIX + spotifyId
    fun streamUri(spotifyId: String, durationMs: Long) = "$SCHEME://$spotifyId?ms=$durationMs"

    /** The Spotify id of a Slowva track id or stream uri; null for anything else. */
    fun spotifyId(idOrUri: String): String? = when {
        idOrUri.startsWith(PREFIX) -> idOrUri.removePrefix(PREFIX)
        idOrUri.startsWith("$SCHEME://") -> idOrUri.removePrefix("$SCHEME://").substringBefore('?').substringBefore('/')
        else -> null
    }?.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }

    fun isSpotify(t: Track) = t.uri.startsWith("$SCHEME://")
}

/** Web API JSON to models; pure, so it is unit tested. */
object SpotifyParse {
    fun track(o: JSONObject, album: String? = null, art: String? = null): SpTrack? {
        val id = o.optString("id").takeIf { it.isNotBlank() && !o.optBoolean("is_local") } ?: return null
        // podcasts episodes come through playlists too
        if (o.optString("type", "track") != "track") return null
        val a = o.optJSONObject("album")
        return SpTrack(
            id = id,
            name = o.optString("name"),
            artists = artists(o),
            album = album ?: a?.optString("name").orEmpty(),
            durationMs = o.optLong("duration_ms"),
            art = art ?: a?.let(::image),
            playable = o.optBoolean("is_playable", true),
        )
    }

    fun album(o: JSONObject): SpAlbum? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        return SpAlbum(id, o.optString("name"), artists(o).joinToString(", ") { it.name }, image(o), o.optInt("total_tracks"))
    }

    fun playlist(o: JSONObject): SpPlaylist? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val owner = o.optJSONObject("owner")?.let { it.optString("display_name").ifBlank { it.optString("id") } }.orEmpty()
        // newer replies name the count "items", older ones "tracks"
        val count = (o.optJSONObject("items") ?: o.optJSONObject("tracks"))?.optInt("total") ?: 0
        return SpPlaylist(id, o.optString("name"), owner, image(o), count)
    }

    fun artist(o: JSONObject): SpArtist? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val g = o.optJSONArray("genres")
        return SpArtist(id, o.optString("name"), image(o), (0 until (g?.length() ?: 0)).mapNotNull { g!!.optString(it).ifBlank { null } })
    }

    /** A playlist row: the song sits under "item" in current replies, "track" in older ones. */
    fun playlistItem(o: JSONObject): SpTrack? = (o.optJSONObject("item") ?: o.optJSONObject("track"))?.let { track(it) }

    /** A saved-tracks or saved-albums row wraps its object. */
    fun savedTrack(o: JSONObject): SpTrack? = o.optJSONObject("track")?.let { track(it) }
    fun savedAlbum(o: JSONObject): SpAlbum? = o.optJSONObject("album")?.let(::album)

    fun search(o: JSONObject) = SpResults(
        tracks = list(o.optJSONObject("tracks")?.optJSONArray("items")) { track(it) },
        albums = list(o.optJSONObject("albums")?.optJSONArray("items"), ::album),
        playlists = list(o.optJSONObject("playlists")?.optJSONArray("items"), ::playlist),
        artists = list(o.optJSONObject("artists")?.optJSONArray("items"), ::artist),
    )

    fun <T> list(arr: JSONArray?, parse: (JSONObject) -> T?): List<T> {
        if (arr == null) return emptyList()
        // search replies hold nulls where an item was removed
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(parse) }
    }

    private fun artists(o: JSONObject): List<SpArtist> =
        list(o.optJSONArray("artists")) { a -> a.optString("name").takeIf { it.isNotBlank() }?.let { SpArtist(a.optString("id"), it) } }

    /** The biggest image; Spotify lists them largest first. */
    private fun image(o: JSONObject): String? =
        o.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
}
