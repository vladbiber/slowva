package com.slowva.player.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import com.slowva.player.ui.NewPlaylist
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.data.MusicRepository
import com.slowva.player.data.Playlist
import com.slowva.player.data.PlaylistRecency
import com.slowva.player.data.Track
import com.slowva.player.recommend.HiddenArtists
import com.slowva.player.ui.EmptyHint
import com.slowva.player.ui.FilterButton
import com.slowva.player.ui.PlaylistArt
import com.slowva.player.ui.PlaylistOptions
import com.slowva.player.ui.RenamePlaylistDialog
import com.slowva.player.ui.SavedFilters
import com.slowva.player.ui.SearchField
import com.slowva.player.ui.SongFilter
import com.slowva.player.ui.SongSort
import com.slowva.player.ui.matches
import com.slowva.player.ui.rememberPlayOrder
import com.slowva.player.ui.rememberSavedFilter
import com.slowva.player.ui.songCount
import com.slowva.player.ui.songs.Snack
import com.slowva.player.ui.songs.SongActions
import com.slowva.player.ui.songs.SongContext
import com.slowva.player.ui.songs.SongRow
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.SurfaceElevated
import com.slowva.player.ui.theme.TextMuted
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * One playlist: Play, Shuffle and Add songs up top, songs below; an empty one shows just a big Add songs.
 * Tap the name to rename it in place; a playlist just made opens with its name ready to type over. Swipe a song away to remove it (with Undo);
 * Reorder shows drag handles and switches the sort to custom order.
 */
@Composable
fun PlaylistStudio(repo: MusicRepository, name: String, onBack: () -> Unit, onRenamed: (String) -> Unit) {
    val playlists by repo.playlists.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.name == name } ?: return EmptyHint("This playlist was deleted.")
    val all by repo.tracks.collectAsStateWithLifecycle()
    val plTracks = remember(playlist, all) { repo.playlistTracks(name) }
    var query by rememberSaveable(name) { mutableStateOf("") }
    val q = query.trim()
    val ctx = LocalContext.current
    val filterKey = SavedFilters.playlistKey(name)
    val (filter, setFilter) = rememberSavedFilter(filterKey)
    val favorites by repo.favorites.collectAsStateWithLifecycle()
    val hidden by HiddenArtists.names.collectAsStateWithLifecycle()
    val plays = rememberPlayOrder(repo, filter.sort == SongSort.PLAYS)
    val listed = filter.apply(plTracks, liked = favorites, hidden = hidden, added = playlist.trackIds, plays = plays)
    val shown = if (q.isEmpty()) listed else listed.filter { it.matches(q) }
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var reordering by remember { mutableStateOf(false) }
    val coverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) repo.setPlaylistCover(name, uri)
    }
    val startReorder = {
        // dragging only makes sense in the playlist's own order
        if (filter.sort != SongSort.CUSTOM) setFilter(filter.copy(sort = SongSort.CUSTOM))
        query = ""
        reordering = true
    }
    val list = rememberLazyListState()
    val rename: (String) -> Unit = { newName ->
        val n = newName.trim()
        if (n.isEmpty() || n == name) Unit
        else if (repo.renamePlaylist(name, n)) {
            // the sort, filters and recency move with the playlist
            SavedFilters.save(ctx, SavedFilters.playlistKey(n), filter); SavedFilters.save(ctx, filterKey, SongFilter())
            PlaylistRecency.rename(name, n)
            onRenamed(n)
        } else Snack.show("A playlist called \"$n\" already exists")
    }
    var editingName by rememberSaveable(name) { mutableStateOf(NewPlaylist.fresh.value == name) }
    LaunchedEffect(name) { if (NewPlaylist.fresh.value == name) NewPlaylist.fresh.value = null }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) }
                Spacer(Modifier.weight(1f))
                if (reordering) TextButton(onClick = { reordering = false }) { Text("Done", color = Accent) }
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, "Playlist options", tint = TextSecondary) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = SurfaceElevated) {
                        MenuRow(Icons.Filled.Edit, "Rename") { showMenu = false; showRename = true }
                        if (plTracks.size > 1) MenuRow(Icons.Filled.SwapVert, "Reorder songs") { showMenu = false; startReorder() }
                        MenuRow(Icons.Filled.Image, "Change cover") { showMenu = false; coverLauncher.launch(arrayOf("image/*")) }
                        DropdownMenuItem(
                            text = { Text("Delete playlist", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { showMenu = false; showDelete = true },
                        )
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PlaylistArt(playlist, plTracks.firstOrNull(), 180.dp, shape = MaterialTheme.shapes.large)
                Spacer(Modifier.height(14.dp))
                if (editingName) NameField(name, onDone = { editingName = false; rename(it) })
                else Text(name, color = TextPrimary, style = MaterialTheme.typography.headlineMedium, maxLines = 2, textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable(onClickLabel = "rename") { editingName = true }.semantics { heading() })
                Text("Playlist · ${songCount(plTracks.size)}", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                if (plTracks.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    PlayShuffleRow(listed, repo)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add songs") }
                        if (plTracks.size > 1 && !reordering) OutlinedButton(onClick = startReorder) { Icon(Icons.Filled.SwapVert, null); Spacer(Modifier.width(6.dp)); Text("Reorder") }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        if (!reordering && plTracks.size > 6) item {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, { query = it }, "Search this playlist", Modifier.weight(1f))
                FilterButton(filter.active, onReset = { setFilter(SongFilter()) }) { PlaylistOptions(filter, setFilter) }
            }
        }
        if (plTracks.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Add songs", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(10.dp))
                Text("Search your library and tick the songs you want.", color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        } else if (!reordering && shown.isEmpty()) item { EmptyHint(if (q.isEmpty()) "No songs here match these filters" else "No songs here match \"$q\"") }
        if (reordering) reorderRows(repo, name, plTracks, list)
        else items(shown, key = { it.id }) { t ->
            SwipeToRemove(onRemove = { SongActions.remove(repo, SongContext.Playlist(name), listOf(t.id)) }) {
                SongRow(t, SongContext.Playlist(name), shown, onPlay = { PlayList.play(repo, shown, shown.indexOf(t)) })
            }
        }
    }

    if (showAdd) AddSongsSheet(repo, name, onDismiss = { showAdd = false })
    if (showRename) RenamePlaylistDialog(currentName = name, onRename = rename, onDismiss = { showRename = false })
    if (showDelete) DeleteDialog(repo, playlist, plTracks, onDismiss = { showDelete = false }, onDeleted = onBack)
}

/** The playlist name as a field, all selected; Done, the check or tapping elsewhere keeps it. */
@Composable
private fun NameField(name: String, onDone: (String) -> Unit) {
    // Done then the focus loss that follows must save only once
    var saved by remember { mutableStateOf(false) }
    val save = { text: String -> if (!saved) { saved = true; onDone(text) } }
    var value by remember(name) { mutableStateOf(TextFieldValue(name, TextRange(0, name.length))) }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = value, onValueChange = { value = it }, singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
        placeholder = { Text("Playlist name") },
        trailingIcon = { IconButton(onClick = { save(value.text) }) { Icon(Icons.Filled.Check, "Save name", tint = Accent) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save(value.text) }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged {
            if (focused && !it.isFocused) save(value.text)
            focused = it.isFocused
        },
    )
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) = DropdownMenuItem(
    text = { Text(label, color = TextPrimary) },
    leadingIcon = { Icon(icon, null, tint = TextSecondary) },
    onClick = onClick,
)

/** Swipe left to remove; the snackbar's Undo puts the song back. */
@Composable
private fun SwipeToRemove(onRemove: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onRemove() },
        backgroundContent = {
            Box(Modifier.fillMaxSize().padding(horizontal = 8.dp).background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small),
                contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(end = 20.dp))
            }
        },
    ) { Box(Modifier.background(MaterialTheme.colorScheme.background)) { content() } }
}

/** The whole playlist with drag handles; the list scrolls by itself while a song is held near its edges. */
private fun LazyListScope.reorderRows(repo: MusicRepository, name: String, tracks: List<Track>, list: androidx.compose.foundation.lazy.LazyListState) {
    fun indexOf(id: String) = repo.playlists.value.firstOrNull { it.name == name }?.trackIds?.indexOf(id) ?: -1
    itemsIndexed(tracks, key = { _, t -> "r" + t.id }) { i, t ->
        val key = "r" + t.id
        val scope = rememberCoroutineScope()
        var dragging by remember { mutableStateOf(false) }
        var dragStart by remember { mutableFloatStateOf(0f) }
        var dragDelta by remember { mutableFloatStateOf(0f) }
        ReorderRow(
            t, i, tracks.size,
            modifier = if (dragging) Modifier.zIndex(1f).graphicsLayer {
                val now = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset ?: 0
                translationY = dragStart + dragDelta - now
            } else Modifier.animateItem(),
            handle = Modifier.pointerInput(key) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true; dragDelta = 0f
                        dragStart = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset?.toFloat() ?: 0f
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, amount ->
                    change.consume()
                    dragDelta += amount.y
                    val info = list.layoutInfo
                    val me = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return@detectDragGestures
                    val top = dragStart + dragDelta
                    val edge = me.size * 1.2f
                    // near an edge: scroll, and keep the row under the finger
                    val step = when {
                        top < info.viewportStartOffset + edge -> -24f
                        top + me.size > info.viewportEndOffset - edge -> 24f
                        else -> 0f
                    }
                    if (step != 0f) scope.launch { list.scrollBy(step) }
                    val middle = (top + me.size / 2).toInt()
                    val over = info.visibleItemsInfo.firstOrNull {
                        it.key != key && (it.key as? String)?.startsWith("r") == true && middle in it.offset until it.offset + it.size
                    } ?: return@detectDragGestures
                    repo.moveInPlaylist(name, indexOf(t.id), indexOf((over.key as String).removePrefix("r")))
                }
            },
        ) { to -> repo.moveInPlaylist(name, indexOf(t.id), to) }
    }
}

/** One song while a playlist is being reordered: drag the handle, or use the accessibility actions. */
@Composable
private fun ReorderRow(track: Track, index: Int, size: Int, modifier: Modifier, handle: Modifier, onMove: (Int) -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 4.dp).semantics(mergeDescendants = true) {
            customActions = listOfNotNull(
                CustomAccessibilityAction("Move to top") { onMove(0); true }.takeIf { index > 0 },
                CustomAccessibilityAction("Move up") { onMove(index - 1); true }.takeIf { index > 0 },
                CustomAccessibilityAction("Move down") { onMove(index + 1); true }.takeIf { index < size - 1 },
            )
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}", color = TextMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Filled.DragHandle, "Drag to move ${track.title}", tint = TextSecondary, modifier = handle.size(48.dp).padding(12.dp))
    }
}

@Composable
private fun DeleteDialog(repo: MusicRepository, playlist: Playlist, tracks: List<Track>, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val name = playlist.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete playlist?", color = TextPrimary) },
        text = {
            val online = tracks.count { it.isOnline }
            Column {
                Text("\"$name\" will be deleted. The songs stay in your library.", color = TextSecondary)
                if (online > 0) TextButton(onClick = {
                    onDismiss()
                    Snack.show("Deleted \"$name\" and its saved online songs", repo.deletePlaylistWithStreams(name))
                    onDeleted()
                }) { Text("Delete it and its $online saved online songs", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                Snack.show("Deleted \"$name\"", repo.deletePlaylist(name))
                onDeleted()
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
        containerColor = SurfaceElevated,
    )
}
