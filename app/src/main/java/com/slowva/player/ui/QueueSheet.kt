package com.slowva.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.slowva.player.SlowvaApp
import com.slowva.player.player.PlayerHub
import com.slowva.player.recommend.UpNext
import com.slowva.player.ui.songs.SongContext
import com.slowva.player.ui.songs.SongSheets
import com.slowva.player.ui.songs.SongTarget
import com.slowva.player.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** The play queue as a full sheet: tap plays, drag the handle to move, swipe left to take a song out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(onDismiss: () -> Unit) {
    val repository = (LocalContext.current.applicationContext as SlowvaApp).repository
    val ui by PlayerHub.ui.collectAsState()
    val online by com.slowva.player.recommend.SongRadio.provider.collectAsState()
    val recommended by UpNext.recommended.collectAsState()
    val loading by UpNext.loading.collectAsState()
    val error by UpNext.error.collectAsState()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = ui.currentIndex.coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    // the held song follows the finger; the service gets one move when it is dropped
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    val ids = ui.queueIds

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f)) {
            Text("Up next", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp).semantics { heading() })
            // recommendations need an online source; without one the queue is library only
            if (online != null) PillTabs(listOf("Library", "Recommended"), if (recommended) 1 else 0, { UpNext.choose(it == 1) }, Modifier.padding(horizontal = 8.dp))
            val note = if (loading) "Finding songs like this one…" else error
            if (note != null) Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (loading) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(note, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
                // a queue can hold a song twice, so the position is part of the key
                itemsIndexed(ids, key = { i, id -> "$i:$id" }) { index, id ->
                    val t = repository.trackById(id) ?: return@itemsIndexed
                    val current = index == ui.currentIndex
                    val dragging = index == dragFrom
                    val row = @Composable {
                        Row(
                            Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .then(if (dragging) Modifier.zIndex(1f).graphicsLayer { translationY = dragDelta } else Modifier)
                                .semantics {
                                    customActions = listOfNotNull(
                                        CustomAccessibilityAction("Move up") { PlayerHub.moveInQueue(index, index - 1); true }.takeIf { index > 0 },
                                        CustomAccessibilityAction("Move down") { PlayerHub.moveInQueue(index, index + 1); true }.takeIf { index < ids.size - 1 },
                                    )
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f)) {
                                TrackRow(
                                    track = t, isCurrent = current, isPlaying = current && ui.isPlaying, isFavorite = false,
                                    onClick = { PlayerHub.playQueueIndex(index) }, onToggleFavorite = null,
                                    onMore = { SongSheets.show(SongTarget.Song(t), SongContext.Other) },
                                )
                            }
                            Icon(
                                Icons.Filled.DragHandle, "Drag to move ${t.title}", tint = TextSecondary,
                                modifier = Modifier.size(48.dp).padding(12.dp).pointerInput(id, index) {
                                    var rowH = 1f
                                    detectDragGestures(
                                        onDragStart = {
                                            dragFrom = index; dragDelta = 0f
                                            rowH = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size?.toFloat()?.coerceAtLeast(1f) ?: 1f
                                        },
                                        onDragEnd = {
                                            // one row height dragged = one place moved
                                            val to = (dragFrom + kotlin.math.round(dragDelta / rowH).toInt()).coerceIn(0, ids.size - 1)
                                            if (dragFrom >= 0 && to != dragFrom) PlayerHub.moveInQueue(dragFrom, to)
                                            dragFrom = -1; dragDelta = 0f
                                        },
                                        onDragCancel = { dragFrom = -1; dragDelta = 0f },
                                    ) { change, amount ->
                                        change.consume()
                                        dragDelta += amount.y
                                        val info = list.layoutInfo
                                        val me = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@detectDragGestures
                                        val top = me.offset + dragDelta
                                        val step = when {
                                            top < info.viewportStartOffset + rowH -> -20f
                                            top + rowH > info.viewportEndOffset - rowH -> 20f
                                            else -> 0f
                                        }
                                        // scrolling moves the list under the finger, so the held row keeps up
                                        if (step != 0f) scope.launch { dragDelta += list.scrollBy(step) }
                                    }
                                },
                            )
                        }
                    }
                    if (current || dragFrom >= 0) row()
                    else {
                        val state = rememberSwipeToDismissBoxState()
                        SwipeToDismissBox(
                            state = state, enableDismissFromStartToEnd = false,
                            onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) PlayerHub.removeFromQueue(index) },
                            backgroundContent = {
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer), contentAlignment = Alignment.CenterEnd) {
                                    Icon(Icons.Filled.Delete, "Remove from queue", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(end = 20.dp))
                                }
                            },
                        ) { row() }
                    }
                }
            }
        }
    }
}
