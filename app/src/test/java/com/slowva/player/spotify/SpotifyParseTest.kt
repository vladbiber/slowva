package com.slowva.player.spotify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyParseTest {
    private val trackJson = """
        {"id":"4uLU6hMCjMI75M1A2tKUQC","type":"track","name":"Never Gonna Give You Up","duration_ms":213573,
         "artists":[{"id":"0gxyHStUsqpMadRV0Di1Qt","name":"Rick Astley"},{"id":"x","name":"Guest"}],
         "album":{"id":"6N9PS4QXF1D0OWPk0Sxtb4","name":"Whenever You Need Somebody",
                  "images":[{"url":"https://i.scdn.co/image/big","width":640},{"url":"https://i.scdn.co/image/small","width":64}]}}
    """.trimIndent()

    @Test
    fun trackBecomesASpotifyStreamTrack() {
        val t = SpotifyParse.track(JSONObject(trackJson))!!
        assertEquals("Rick Astley, Guest", t.artist)
        assertEquals("https://i.scdn.co/image/big", t.art)
        val track = t.toTrack()
        assertEquals("sp:4uLU6hMCjMI75M1A2tKUQC", track.id)
        assertEquals("spstream://4uLU6hMCjMI75M1A2tKUQC?ms=213573", track.uri)
        assertEquals("Whenever You Need Somebody", track.album)
        assertEquals(213573L, track.durationMs)
        assertTrue(track.isOnline)
        assertTrue(SpotifyIds.isSpotify(track))
    }

    @Test
    fun localFilesEpisodesAndRemovedItemsAreSkipped() {
        assertNull(SpotifyParse.track(JSONObject("""{"id":"a","type":"episode","name":"Pod"}""")))
        assertNull(SpotifyParse.track(JSONObject("""{"id":"","name":"x"}""")))
        assertNull(SpotifyParse.track(JSONObject("""{"id":"a","is_local":true,"name":"x"}""")))
    }

    @Test
    fun playlistRowsUseItemOrTrack() {
        val inner = JSONObject(trackJson)
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", SpotifyParse.playlistItem(JSONObject().put("item", inner))?.id)
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", SpotifyParse.playlistItem(JSONObject().put("track", inner))?.id)
        assertNull(SpotifyParse.playlistItem(JSONObject().put("track", JSONObject.NULL)))
    }

    @Test
    fun searchKeepsEveryKindAndSkipsNulls() {
        val r = SpotifyParse.search(JSONObject("""
            {"tracks":{"items":[$trackJson, null]},
             "albums":{"items":[{"id":"al","name":"Album","artists":[{"name":"A"}],"images":[{"url":"u"}],"total_tracks":12}]},
             "playlists":{"items":[null,{"id":"pl","name":"Mix","owner":{"display_name":"Vlad"},"items":{"total":40}}]},
             "artists":{"items":[{"id":"ar","name":"Rick Astley","genres":["dance pop"],"images":[]}]}}
        """.trimIndent()))
        assertEquals(1, r.tracks.size)
        assertEquals(SpAlbum("al", "Album", "A", "u", 12), r.albums.single())
        assertEquals(SpPlaylist("pl", "Mix", "Vlad", null, 40), r.playlists.single())
        assertEquals(listOf("dance pop"), r.artists.single().genres)
        assertFalse(r.isEmpty)
    }

    @Test
    fun savedRowsUnwrap() {
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", SpotifyParse.savedTrack(JSONObject().put("track", JSONObject(trackJson)))?.id)
        val album = SpotifyParse.savedAlbum(JSONObject("""{"album":{"id":"al","name":"Album","artists":[{"name":"A"},{"name":"B"}]}}"""))
        assertEquals("A, B", album?.artist)
        assertEquals("pl2", SpotifyParse.playlist(JSONObject("""{"id":"pl2","name":"x","owner":{"id":"me"},"tracks":{"total":3}}"""))?.let { "${it.id}" })
        assertEquals(3, SpotifyParse.playlist(JSONObject("""{"id":"pl2","name":"x","owner":{"id":"me"},"tracks":{"total":3}}"""))?.tracks)
    }

    @Test
    fun idsRoundTrip() {
        assertEquals("abc123", SpotifyIds.spotifyId("sp:abc123"))
        assertEquals("abc123", SpotifyIds.spotifyId("spstream://abc123?ms=1000"))
        assertNull(SpotifyIds.spotifyId("file:///x.mp3"))
        assertNull(SpotifyIds.spotifyId("spstream://../etc?ms=1"))
    }
}
