package com.slowva.player.ui.home

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything Home can show, in the default order; [widget]s are off until added.
 * [online] shelves are filled by the online source (SongRadio.provider) and offered only once one is set.
 */
enum class HomeSection(val id: String, val title: String, val icon: ImageVector, val widget: Boolean = false, val online: Boolean = false) {
    NOW_PLAYING("w_now", "Now playing", Icons.Filled.PlayCircle, widget = true),
    VINYL("w_vinyl", "Vinyl", Icons.Filled.Album, widget = true),
    CD("w_cd", "CD", Icons.Filled.Adjust, widget = true),
    TAPE("w_tape", "Cassette", Icons.Filled.Voicemail, widget = true),
    TP7("w_tp7", "TP-7", Icons.Filled.SettingsInputComponent, widget = true),
    RADIO("w_radio", "Radio", Icons.Filled.Radio, widget = true),
    QUICK_PICKS("quick", "Quick picks", Icons.Filled.GridView),
    CONTINUE("continue", "Continue listening", Icons.Filled.Replay),
    FOR_YOU("for_you", "For you", Icons.Filled.AutoAwesome, online = true),
    RECENT("recent", "Recently played", Icons.Filled.History),
    PLAYLISTS("playlists", "Your playlists", Icons.AutoMirrored.Filled.QueueMusic),
    SPOTIFY_LIKED("sp_liked", "Liked on Spotify", Icons.Filled.Favorite, online = true),
    SPOTIFY_PLAYLISTS("sp_playlists", "Spotify playlists", Icons.AutoMirrored.Filled.PlaylistPlay, online = true),
    STARS("stars", "From your stars", Icons.Filled.Star, online = true),
    PLAYED("played", "From what you played", Icons.AutoMirrored.Filled.TrendingUp, online = true),
    REDISCOVER("rediscover", "Rediscover", Icons.Filled.Album),
    MOST("most", "Most played", Icons.Filled.BarChart),
    GENRES("genres", "Genres", Icons.Filled.Category),
    CREATORS("creators", "Creators", Icons.Filled.People),
    WORLD("world", "Around the world", Icons.Filled.Public),
    VISUALIZER("w_viz", "Live visualizer", Icons.Filled.Equalizer, widget = true),
    SHORTCUTS("w_shortcuts", "Shortcuts", Icons.Filled.TouchApp, widget = true),
    STATS("w_stats", "Your music in numbers", Icons.Filled.Insights, widget = true),
    CLOCK("w_clock", "Clock", Icons.Filled.AccessTime, widget = true);

    /** One line for the editor, under the name. */
    val hint: String get() = when (this) {
        NOW_PLAYING -> "Big cover, controls and the time bar"
        VINYL -> "The record from the player, spinning"
        CD -> "The CD from the player"
        TAPE -> "The cassette from the player"
        TP7 -> "The TP-7 from the player"
        RADIO -> "A radio playing your song"
        VISUALIZER -> "Bars that dance to what's playing"
        SHORTCUTS -> "Shuffle liked, shuffle all, most played, spin the wheel"
        STATS -> "Songs, liked, playlists and artists"
        CLOCK -> "Time and date in big type"
        else -> if (widget) "" else "Shelf"
    }

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
    }
}

/** An ordered set of sections kept in the "slowva-ui" prefs (so backup carries it); [defaults] show until the user edits. */
class Board(
    private val key: String,
    private val defaults: List<HomeSection>,
    /** what the editor lets you add here */
    val offered: List<HomeSection>,
) {
    private var prefs: SharedPreferences? = null
    private val state = MutableStateFlow(defaults)
    val shown: StateFlow<List<HomeSection>> = state.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val saved = p.getString("${key}_order", null) ?: return
        // every id the user has seen, so a section added in a later version shows up without bringing back removed ones
        val known = p.getString("${key}_known", "").orEmpty().split(',').toSet()
        val order = saved.split(',').filter { it.isNotBlank() }.mapNotNull(HomeSection::byId).filter { it in offered }.distinct()
        state.value = order + defaults.filter { it.id !in known && it !in order }
    }

    fun set(list: List<HomeSection>) {
        state.value = list.distinct()
        prefs?.edit()
            ?.putString("${key}_order", state.value.joinToString(",") { it.id })
            ?.putString("${key}_known", HomeSection.entries.joinToString(",") { it.id })
            ?.apply()
    }

    fun reset() {
        state.value = defaults
        prefs?.edit()?.remove("${key}_order")?.remove("${key}_known")?.apply()
    }

    private companion object {
        const val PREFS = "slowva-ui"
    }
}

/** Home: the shelves, plus any widget. */
val HomeLayout = Board("home", HomeSection.entries.filterNot { it.widget }, HomeSection.entries)

/** Library: a strip of widgets above the tabs, empty until you add some. */
val LibraryLayout = Board("library", emptyList(), HomeSection.entries.filter { it.widget })
