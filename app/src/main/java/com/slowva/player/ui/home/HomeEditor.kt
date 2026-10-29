package com.slowva.player.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.slowva.player.lastfm.LastFm
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.TextMuted
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** A board's editor: drag ≡ to reorder, − takes a section off, + puts it (or a widget) on. Changes apply as you go. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeEditor(onDismiss: () -> Unit, board: Board = HomeLayout, title: String = "Edit Home", place: String = "Home") {
    // the World shelf needs a Last.fm key and the online shelves an online source; without them they would never show
    val online = com.slowva.player.recommend.SongRadio.provider.collectAsState().value != null
    val offered: (HomeSection) -> Boolean = { it in board.offered && (it != HomeSection.WORLD || LastFm.enabled) && (!it.online || online) }
    val list = remember { mutableStateListOf<HomeSection>().apply { addAll(board.shown.value) } }
    val save = { board.set(list.toList()) }
    val off = HomeSection.entries.filter { it !in list && offered(it) }
    val lazy = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf<HomeSection?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }

    fun move(from: Int, to: Int) {
        if (to !in list.indices || from == to) return
        // moving the first visible row would drag the scroll position along with it
        val first = lazy.firstVisibleItemIndex
        val firstOffset = lazy.firstVisibleItemScrollOffset
        list.add(to, list.removeAt(from))
        scope.launch { lazy.scrollToItem(first, firstOffset) }
        save()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // the handles drag rows; the sheet itself stays put
        sheetGesturesEnabled = false,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(state = lazy, contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "title") {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { board.reset(); list.clear(); list.addAll(board.shown.value) }) { Text("Reset") }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                Text(
                    "Hold ≡ and drag to move a row. − takes it off $place, + brings it back.",
                    color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Label("On $place")
                if (list.isEmpty()) Text(
                    "Nothing here yet. Add a widget from the list below.", color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            // sections that can't show yet stay in the saved order but out of sight
            items(list.filter(offered), key = { it.id }) { s ->
                val me = s == dragging
                val index = list.indexOf(s)
                EditorRow(
                    s, on = true,
                    modifier = if (me) Modifier.zIndex(1f).graphicsLayer { translationY = offset }.shadow(8.dp, MaterialTheme.shapes.medium)
                    else Modifier.animateItem(),
                    lifted = me,
                    onToggle = { list.remove(s); save() },
                    moveActions = listOf(
                        CustomAccessibilityAction("Move up") { move(index, index - 1); true },
                        CustomAccessibilityAction("Move down") { move(index, index + 1); true },
                    ),
                    handle = Modifier.pointerInput(s) {
                        detectDragGestures(
                            onDragStart = { dragging = s; offset = 0f },
                            onDragEnd = { dragging = null; offset = 0f },
                            onDragCancel = { dragging = null; offset = 0f },
                        ) { change, amount ->
                            change.consume()
                            offset += amount.y
                            val info = lazy.layoutInfo
                            val mine = info.visibleItemsInfo.firstOrNull { it.key == s.id } ?: return@detectDragGestures
                            val center = mine.offset + offset + mine.size / 2f
                            val over = info.visibleItemsInfo.firstOrNull { other ->
                                other.key != s.id && list.any { it.id == other.key } && center > other.offset && center < other.offset + other.size
                            }
                            if (over != null) {
                                val from = list.indexOf(s)
                                val to = list.indexOfFirst { it.id == over.key }
                                // keep the row under the finger: it now sits where the other one was
                                offset += mine.offset - over.offset
                                move(from, to)
                            }
                            // near the top or bottom edge, scroll so rows off screen can be reached
                            val top = mine.offset + offset
                            val step = when {
                                top < info.viewportStartOffset + 40 -> -24f
                                top + mine.size > info.viewportEndOffset - 40 -> 24f
                                else -> 0f
                            }
                            if (step != 0f) scope.launch { offset += lazy.scrollBy(step) }
                        }
                    },
                )
            }
            val widgets = off.filter { it.widget }
            val shelves = off.filterNot { it.widget }
            if (widgets.isNotEmpty()) {
                item(key = "widgets") { Label("Widgets you can add") }
                items(widgets, key = { it.id }) { s -> EditorRow(s, on = false, modifier = Modifier.animateItem(), onToggle = { list.add(0, s); save() }) }
            }
            if (shelves.isNotEmpty()) {
                item(key = "removed") { Label("Taken off $place") }
                items(shelves, key = { it.id }) { s -> EditorRow(s, on = false, modifier = Modifier.animateItem(), onToggle = { list.add(s); save() }) }
            }
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, color = TextMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp))

@Composable
private fun EditorRow(
    s: HomeSection,
    on: Boolean,
    modifier: Modifier,
    lifted: Boolean = false,
    onToggle: () -> Unit,
    moveActions: List<CustomAccessibilityAction> = emptyList(),
    handle: Modifier? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (lifted) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer)
            .heightIn(min = 60.dp)
            .semantics { if (moveActions.isNotEmpty()) customActions = moveActions },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggle) {
            if (on) Icon(Icons.Filled.RemoveCircleOutline, "Take ${s.title} off", tint = MaterialTheme.colorScheme.error)
            else Icon(Icons.Filled.AddCircle, "Add ${s.title}", tint = Accent)
        }
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(s.icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            Text(s.title, color = TextPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = if (s.widget) "Widget · ${s.hint}" else s.hint
            Text(sub, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (handle != null) Box(handle.size(52.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.DragHandle, "Drag to move ${s.title}", tint = if (lifted) Accent else TextSecondary)
        }
        else Spacer(Modifier.width(12.dp))
    }
}
