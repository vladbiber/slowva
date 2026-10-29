package com.slowva.player.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.data.MusicRepository
import com.slowva.player.data.Track
import com.slowva.player.recommend.HiddenArtists
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.SurfaceDark
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.Serializable

/** Which part of a song the query has to hit. */
enum class Match(val label: String) { SONGS("Songs"), ARTISTS("Artists"), ALBUMS("Albums") }

enum class Length(val label: String, val range: LongRange) {
    ANY("Any length", 0..Long.MAX_VALUE),
    SHORT("Under 3 min", 0 until 180_000L),
    MID("3 to 6 min", 180_000L..360_000L),
    LONG("Over 6 min", 360_001L..Long.MAX_VALUE),
}

enum class SongSort(val label: String) { CUSTOM("Custom order"), TITLE("Title"), ARTIST("Artist"), ADDED("Date added"), PLAYS("Most played") }

/** Narrows and orders library songs; search uses [match] and [length], playlists [sort] and [hideHidden]. */
data class SongFilter(
    val match: Match = Match.SONGS,
    val downloadedOnly: Boolean = false,
    val likedOnly: Boolean = false,
    val hideHidden: Boolean = false,
    val length: Length = Length.ANY,
    val sort: SongSort = SongSort.CUSTOM,
) : Serializable {
    val active get() = this != SongFilter()

    /**
     * [added] are ids oldest first, [plays] ids most played first; songs missing from either go last.
     * Sorts are stable, so ties keep the custom order.
     */
    fun apply(
        tracks: List<Track>,
        q: String = "",
        liked: Set<String> = emptySet(),
        hidden: Set<String> = emptySet(),
        added: List<String> = emptyList(),
        plays: List<String> = emptyList(),
    ): List<Track> {
        var out = tracks.filter { t ->
            (q.isEmpty() || when (match) {
                Match.SONGS -> t.matches(q)
                Match.ARTISTS -> t.artist.contains(q, true)
                Match.ALBUMS -> t.album.contains(q, true)
            }) && (!downloadedOnly || !t.isOnline) && (!likedOnly || t.id in liked) &&
                (length == Length.ANY || t.durationMs > 0 && t.durationMs in length.range)
        }
        if (hideHidden) out = HiddenArtists.filter(out, hidden) { it.artist }
        return when (sort) {
            SongSort.CUSTOM -> out
            SongSort.TITLE -> out.sortedBy { it.title.lowercase() }
            SongSort.ARTIST -> out.sortedBy { it.artist.lowercase() }
            SongSort.ADDED -> added.positions().let { pos -> out.sortedByDescending { pos[it.id] ?: -1 } }
            SongSort.PLAYS -> plays.positions().let { pos -> out.sortedBy { pos[it.id] ?: Int.MAX_VALUE } }
        }
    }

    /** For SharedPreferences: sort, downloaded, liked, hide hidden. */
    fun encode() = "${sort.name},${downloadedOnly.bit},${likedOnly.bit},${hideHidden.bit}"

    companion object {
        private fun List<String>.positions() = withIndex().associate { it.value to it.index }
        private val Boolean.bit get() = if (this) 1 else 0

        fun decode(s: String?): SongFilter = runCatching {
            val p = s!!.split(",")
            SongFilter(sort = SongSort.valueOf(p[0]), downloadedOnly = p[1] == "1", likedOnly = p[2] == "1", hideHidden = p[3] == "1")
        }.getOrDefault(SongFilter())
    }
}

/** Per playlist sort and filters; Liked songs uses [LIKED]. */
object SavedFilters {
    const val LIKED = "liked"
    fun playlistKey(name: String) = "pl:$name"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("song_filters", Context.MODE_PRIVATE)
    fun load(ctx: Context, key: String) = SongFilter.decode(prefs(ctx).getString(key, null))
    fun save(ctx: Context, key: String, f: SongFilter) =
        prefs(ctx).edit().apply { if (f.active) putString(key, f.encode()) else remove(key) }.apply()
}

/** A [SavedFilters] entry as state; writes through on every change. */
@Composable
internal fun rememberSavedFilter(key: String): Pair<SongFilter, (SongFilter) -> Unit> {
    val ctx = LocalContext.current
    var f by remember(key) { mutableStateOf(SavedFilters.load(ctx, key)) }
    return f to { new -> f = new; SavedFilters.save(ctx, key, new) }
}

/** Ids most played first, only read while [on]. */
@Composable
internal fun rememberPlayOrder(repo: MusicRepository, on: Boolean): List<String> {
    val flow = remember(on) { if (on) repo.mostPlayed(Int.MAX_VALUE).map { l -> l.map { it.id } } else flowOf(emptyList()) }
    // shortcut: mostPlayed only ranks songs played twice or more, once-played ones sit with the unplayed; add a count query if that matters
    return flow.collectAsStateWithLifecycle(emptyList()).value
}

/** The filter icon next to a search field; a dot shows while [active]. Tapping opens a sheet with [content]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilterButton(active: Boolean, onReset: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        CtIconButton(Icons.Filled.Tune, if (active) "Filters, some on" else "Filters", { open = true }, tint = if (active) Accent else TextSecondary)
        if (active) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(8.dp).clip(CircleShape).background(Accent))
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, containerColor = SurfaceDark) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", color = TextPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onReset, enabled = active) { Text("Reset") }
            }
            content()
        }
    }
}

@Composable
internal fun FilterLabel(text: String, section: Boolean = false) = Text(
    text, color = if (section) TextPrimary else TextSecondary,
    style = if (section) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
    modifier = Modifier.padding(top = if (section) 20.dp else 12.dp, bottom = 4.dp).semantics { heading() },
)

@Composable
internal fun <T> FilterChoice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
internal fun FilterSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Library modifiers for a search: what to match, where, and how long. */
@Composable
internal fun SearchSongOptions(f: SongFilter, onChange: (SongFilter) -> Unit) {
    FilterLabel("Search by")
    FilterChoice(Match.entries, f.match, { it.label }) { onChange(f.copy(match = it)) }
    FilterSwitch("On this phone only", f.downloadedOnly) { onChange(f.copy(downloadedOnly = it)) }
    FilterSwitch("Liked only", f.likedOnly) { onChange(f.copy(likedOnly = it)) }
    FilterLabel("Length")
    FilterChoice(Length.entries, f.length, { it.label }) { onChange(f.copy(length = it)) }
}

/** Sort and filters inside a playlist or Liked songs; [liked] hides the redundant Liked only. */
@Composable
internal fun PlaylistOptions(f: SongFilter, onChange: (SongFilter) -> Unit, liked: Boolean = false) {
    FilterLabel("Sort by")
    FilterChoice(SongSort.entries, f.sort, { it.label }) { onChange(f.copy(sort = it)) }
    if (!liked && f.sort != SongSort.CUSTOM) Text("Dragging to reorder works in custom order.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
    FilterLabel("Show")
    FilterSwitch("On this phone only", f.downloadedOnly) { onChange(f.copy(downloadedOnly = it)) }
    if (!liked) FilterSwitch("Liked only", f.likedOnly) { onChange(f.copy(likedOnly = it)) }
    FilterSwitch("Hide hidden artists", f.hideHidden) { onChange(f.copy(hideHidden = it)) }
}
