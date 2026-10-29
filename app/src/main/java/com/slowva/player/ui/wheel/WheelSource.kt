package com.slowva.player.ui.wheel

import com.slowva.player.data.MusicRepository
import com.slowva.player.data.Track
import com.slowva.player.player.PlayerHub

/** One slice on the wheel. [ref] is the Track (or other item) behind it. */
data class WheelItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val cover: String?,
    val ref: Any,
)

/** Where the wheel's candidates come from. Streaming sources plug in here later. */
interface WheelSource {
    val id: String
    val label: String
    /** what the pool holds, e.g. "songs", for the odds line */
    val noun: String
    /** why this source can't be used now, or null */
    val unavailable: String? get() = null
    /** key for the no-repeat deck; changes when the pool's filter changes */
    val poolKey: String get() = id

    /** the whole pool the winner is drawn from */
    suspend fun pool(): List<WheelItem>

    /** runs while the wheel cruises (stream URL, pre-buffer); false drops the winner */
    suspend fun prepare(winner: WheelItem): Boolean = true

    fun play(winner: WheelItem, wheel: List<WheelItem>)

    fun isFavorite(item: WheelItem): Boolean = false
    fun toggleFavorite(item: WheelItem) {}
}

/** Which part of the library the library source spins over. */
sealed interface LibraryScope {
    data object All : LibraryScope
    data object Favorites : LibraryScope
    data class InPlaylist(val name: String) : LibraryScope
}

internal fun Track.toWheelItem() = WheelItem(id, title, artist, coverUri, this)

class LibrarySource(private val repo: MusicRepository, val scope: LibraryScope) : WheelSource {
    override val id = "library"
    override val label = "Your library"
    override val noun = "songs"
    override val poolKey: String
        get() = when (scope) {
            LibraryScope.All -> "lib"
            LibraryScope.Favorites -> "lib:fav"
            is LibraryScope.InPlaylist -> "lib:pl:${scope.name}"
        }

    override suspend fun pool(): List<WheelItem> = when (scope) {
        LibraryScope.All -> repo.tracks.value
        LibraryScope.Favorites -> repo.favoriteTracks()
        is LibraryScope.InPlaylist -> repo.playlistTracks(scope.name)
    }.map { it.toWheelItem() }

    override fun play(winner: WheelItem, wheel: List<WheelItem>) =
        PlayerHub.playQueue(listOf(winner.id) + wheel.map { it.id }.filter { it != winner.id }.shuffled(), 0)
    override fun isFavorite(item: WheelItem) = repo.isFavorite(item.id)
    override fun toggleFavorite(item: WheelItem) = repo.toggleFavorite(item.id)
}
