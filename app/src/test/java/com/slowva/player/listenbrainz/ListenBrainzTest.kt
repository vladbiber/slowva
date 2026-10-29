package com.slowva.player.listenbrainz

import com.slowva.player.BuildConfig
import com.slowva.player.data.Track
import com.slowva.player.player.PlayCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ListenBrainzTest {
    private fun track(album: String, artist: String = "Radiohead - Topic", duration: Long = 284_000) =
        Track("abc", "Airbag", artist, album, "spstream://abc", null, duration, false)

    @Test fun singleListenPayload() {
        val body = ListenBrainz.body("single", listOf(ListenBrainz.listen(track("OK Computer"), 1_700_000_000)))
        assertEquals("single", body.getString("listen_type"))
        val l = body.getJSONArray("payload").getJSONObject(0)
        assertEquals(1_700_000_000, l.getLong("listened_at"))
        val m = l.getJSONObject("track_metadata")
        assertEquals("Radiohead", m.getString("artist_name"))
        assertEquals("Airbag", m.getString("track_name"))
        assertEquals("OK Computer", m.getString("release_name"))
        val info = m.getJSONObject("additional_info")
        assertEquals(284_000, info.getLong("duration_ms"))
        assertEquals("slowva", info.getString("submission_client"))
        assertEquals(BuildConfig.VERSION_NAME, info.getString("submission_client_version"))
    }

    @Test fun playingNowHasNoTimeAndPlaceholderAlbumsAreLeftOut() {
        listOf("Imported", "Imported files", "", "Unknown album").forEach { album ->
            val l = ListenBrainz.listen(track(album, duration = 0), null)
            assertFalse(l.has("listened_at"))
            val m = l.getJSONObject("track_metadata")
            assertFalse(album, m.has("release_name"))
            assertFalse(m.getJSONObject("additional_info").has("duration_ms"))
        }
    }

    @Test fun listenCountsAtHalfOrFourMinutes() {
        assertEquals(60_000, ListenBrainz.thresholdMs(120_000))
        assertEquals(240_000, ListenBrainz.thresholdMs(600_000))
        assertEquals(240_000, ListenBrainz.thresholdMs(0))
    }

    @Test fun counterUsesTheListenBrainzRule() {
        val c = PlayCounter(ListenBrainz::thresholdMs)
        fun play(from: Long, ms: Long, duration: Long) = (0..ms step 250).count { c.update("a", from + it, duration, playing = true) }
        // a 10 minute song: 30 s is not enough, 4 minutes is
        assertEquals(0, play(0, 239_000, 600_000))
        assertEquals(1, play(239_250, 5_000, 600_000))
        val d = PlayCounter(ListenBrainz::thresholdMs)
        assertEquals(0, (0..89_000L step 250).count { d.update("b", it, 180_000, playing = true) })
        assertEquals(1, (90_000..95_000L step 250).count { d.update("b", it, 180_000, playing = true) })
    }
}
