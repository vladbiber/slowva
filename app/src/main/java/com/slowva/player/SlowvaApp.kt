package com.slowva.player

import android.app.Application
import com.slowva.player.data.MusicRepository
import com.slowva.player.data.Ratings
import com.slowva.player.data.db.SlowvaDatabase
import com.slowva.player.diag.Diagnostics
import com.slowva.player.net.NetworkGate

class SlowvaApp : Application() {
    val database: SlowvaDatabase by lazy { SlowvaDatabase.open(this) }
    val repository: MusicRepository by lazy { MusicRepository.get(this, database) }

    override fun onCreate() {
        super.onCreate()
        Diagnostics.init(this, database.diagnostics())
        NetworkGate.init(this)
        com.slowva.player.data.PlaylistRecency.init(this)
        com.slowva.player.data.HistoryPrefs.init(this)
        Ratings.get(this)
        com.slowva.player.listenbrainz.ListenBrainz.init(this)
        com.slowva.player.musicbrainz.MusicBrainz.init(this)
        com.slowva.player.spotify.Spotify.init(this)
    }
}
