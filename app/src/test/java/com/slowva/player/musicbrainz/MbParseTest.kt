package com.slowva.player.musicbrainz

import com.slowva.player.data.Track
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MbParseTest {
    private val search = JSONObject(
        """
        {"created":"2026-10-25T10:00:00.000Z","count":3,"offset":0,"release-groups":[
          {"id":"rg-tribute","type":"Album","score":100,"primary-type":"Album","secondary-types":["Compilation"],
           "title":"OK Computer","first-release-date":"2008-03-01",
           "artist-credit":[{"name":"Various Artists","artist":{"id":"va","name":"Various Artists"}}]},
          {"id":"rg-okc","type":"Album","score":98,"primary-type":"Album","title":"OK Computer",
           "first-release-date":"1997-05-21",
           "artist-credit":[{"name":"Radiohead","artist":{"id":"a74b1b7f","name":"Radiohead"}}],
           "releases":[{"id":"r1","title":"OK Computer","status":"Official"}]},
          {"id":"rg-oknotok","type":"Album","score":80,"primary-type":"Album","secondary-types":["Compilation"],
           "title":"OK Computer OKNOTOK 1997 2017","first-release-date":"2017-06-23",
           "artist-credit":[{"name":"Radiohead","artist":{"id":"a74b1b7f","name":"Radiohead"}}]}
        ]}
        """,
    )

    private val group = JSONObject(
        """
        {"id":"rg-okc","title":"OK Computer","primary-type":"Album","secondary-types":[],"first-release-date":"1997-05-21",
         "rating":{"value":4.35,"votes-count":23},
         "genres":[{"name":"art rock","count":9},{"name":"Alternative Rock","count":14}],
         "tags":[{"name":"seen live","count":30}],
         "releases":[
           {"id":"r-bootleg","status":"Bootleg","date":"1996-01-01"},
           {"id":"r-2009","status":"Official","date":"2009-03-24"},
           {"id":"r-1997","status":"Official","date":"1997-05-21"}
         ]}
        """,
    )

    private val release = JSONObject(
        """
        {"id":"r-1997","title":"OK Computer","media":[
          {"position":1,"format":"CD","track-count":2,"tracks":[
            {"id":"t1","position":1,"number":"1","title":"Airbag","length":284400,"recording":{"id":"x","title":"Airbag","length":284000}},
            {"id":"t2","position":2,"number":"2","title":"Paranoid Android","length":null,"recording":{"id":"y","title":"Paranoid Android","length":387000}}
          ]},
          {"position":2,"format":"CD","tracks":[
            {"id":"t3","position":1,"number":"1","title":"Lull","length":121000}
          ]}
        ]}
        """,
    )

    @Test fun parsesSearchHits() {
        val g = MbParse.groups(search)
        assertEquals(3, g.size)
        assertEquals("Radiohead", g[1].artist)
        assertEquals(1997, g[1].year)
        assertEquals(listOf("Compilation"), g[0].secondaryTypes)
        assertEquals(98, g[1].score)
    }

    @Test fun bestMatchNeedsArtistAndTitle() {
        val g = MbParse.groups(search)
        assertEquals("rg-okc", MbParse.best(g, "Radiohead", "OK Computer")?.id)
        // an edition note on your copy still finds the album
        assertEquals("rg-okc", MbParse.best(g, "Radiohead", "OK Computer (Remastered)")?.id)
        assertNull(MbParse.best(g, "Muse", "OK Computer"))
        assertNull(MbParse.best(g, "Radiohead", "Kid A"))
    }

    @Test fun readsRatingGenresAndRelease() {
        val (value, votes) = MbParse.rating(group)
        assertEquals(4.35, value!!, 0.001)
        assertEquals(23, votes)
        assertEquals(listOf("alternative rock", "art rock"), MbParse.tags(group))
        assertEquals("r-1997", MbParse.pickRelease(group))
    }

    @Test fun ratingWithoutVotesIsNull() {
        val (value, votes) = MbParse.rating(JSONObject("""{"rating":{"value":null,"votes-count":0}}"""))
        assertNull(value)
        assertEquals(0, votes)
    }

    @Test fun readsTracksAcrossDiscs() {
        val t = MbParse.tracks(release)
        assertEquals(3, t.size)
        assertEquals(MbTrack(1, 1, "Airbag", 284400), t[0])
        // a null track length falls back to the recording
        assertEquals(387000, t[1].lengthMs)
        assertEquals(2, t[2].disc)
        assertEquals("4:44", MbParse.lengthLabel(t[0].lengthMs))
    }

    @Test fun albumSurvivesCache() {
        val a = MbAlbum(MbParse.groups(search)[1].copy(score = 0), 4.35, 23, listOf("art rock"), MbParse.tracks(release))
        assertEquals(a, MbAlbum.fromJson(JSONObject(a.toJson().toString())))
        val bare = a.copy(group = a.group.copy(year = null, primaryType = null), rating = null, votes = 0)
        assertEquals(bare, MbAlbum.fromJson(JSONObject(bare.toJson().toString())))
    }

    @Test fun titleKeyDropsVersionNotes() {
        val k = MbParse.titleKey("Paranoid Android")
        assertEquals(k, MbParse.titleKey("Paranoid Android (Remastered 2009)"))
        assertEquals(k, MbParse.titleKey("Paranoid Android - 2017 Remaster"))
        assertEquals(k, MbParse.titleKey("paranoid android [Official Audio]"))
        assertEquals(MbParse.titleKey("Lucky"), MbParse.titleKey("Lucky (feat. Someone)"))
        // words in the real title stay
        assertTrue(MbParse.titleKey("Let Down") != MbParse.titleKey("Let"))
        assertEquals("climbing up the walls", MbParse.titleKey("Climbing Up the Walls"))
    }

    @Test fun matchesYourSongsPreferringThisAlbum() {
        val mb = MbParse.tracks(release)
        fun t(id: String, title: String, album: String) = Track(id, title, "Radiohead", album, "", null, 0, false)
        val local = listOf(
            t("live", "Airbag (Live)", "Live at Somewhere"),
            t("album", "Airbag - Remastered", "OK Computer"),
            t("yt", "Lull", "YouTube"),
        )
        val m = MbParse.matchLocal(mb, local, "OK Computer")
        assertEquals("album", m[0]?.id)
        assertNull(m[1])
        assertEquals("yt", m[2]?.id)
    }

    @Test fun artistSearchAndDiscography() {
        val artists = JSONObject("""{"artists":[{"id":"other","name":"Radiohead Tribute","score":100},{"id":"a74b1b7f","name":"Radiohead","sort-name":"Radiohead","score":99}]}""")
        assertEquals("a74b1b7f", MbParse.artistId(artists, "radiohead"))
        assertNull(MbParse.artistId(JSONObject("""{"artists":[{"id":"z","name":"Someone Else","score":60}]}"""), "Radiohead"))
        val browse = JSONObject(
            """
            {"release-group-count":4,"release-groups":[
              {"id":"kid","title":"Kid A","primary-type":"Album","secondary-types":[],"first-release-date":"2000-10-02"},
              {"id":"pablo","title":"Pablo Honey","primary-type":"Album","secondary-types":[],"first-release-date":"1993-02-22"},
              {"id":"live","title":"I Might Be Wrong","primary-type":"Album","secondary-types":["Live"],"first-release-date":"2001-11-12"},
              {"id":"ep","title":"Drill","primary-type":"EP","secondary-types":[],"first-release-date":"1992-05-05"}
            ]}
            """,
        )
        assertEquals(listOf("pablo", "kid", "ep"), MbParse.discography(browse).map { it.id })
    }
}
