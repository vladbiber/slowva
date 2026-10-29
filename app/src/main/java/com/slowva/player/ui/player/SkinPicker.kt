package com.slowva.player.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.slowva.player.ui.PillTabs
import com.slowva.player.ui.visualizer.Visualizer
import com.slowva.player.ui.visualizer.VisualizerCustomizeSheet
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Swipe through looks drawn still; Select applies the centred one, outside or back cancels.
 * [tabs] are titled sets of items (Skins, Visualizers...); with one set no tabs are shown.
 */
@Composable
fun <T> SkinPicker(
    tabs: List<Pair<String, List<T>>>,
    current: T,
    label: (T) -> String,
    preview: @Composable (item: T, space: DpSize) -> Unit,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Player look",
) {
    var tab by remember { mutableIntStateOf(tabs.indexOfFirst { current in it.second }.coerceAtLeast(0)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column(
                Modifier.padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    title, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                if (tabs.size > 1) PillTabs(tabs.map { it.first }, tab, { tab = it }, Modifier.padding(horizontal = 8.dp))
                key(tab) { LookPager(tabs[tab].second, current, label, preview, onSelect) }
            }
        }
    }
}

@Composable
private fun <T> LookPager(
    items: List<T>,
    current: T,
    label: (T) -> String,
    preview: @Composable (item: T, space: DpSize) -> Unit,
    onSelect: (T) -> Unit,
) {
    val pager = rememberPagerState(initialPage = items.indexOf(current).coerceAtLeast(0)) { items.size }
    val scope = rememberCoroutineScope()
    HorizontalPager(
        state = pager,
        contentPadding = PaddingValues(horizontal = 56.dp),
        modifier = Modifier.fillMaxWidth().height(320.dp),
    ) { page ->
        val item = items[page]
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val off = abs(pager.currentPage - page + pager.currentPageOffsetFraction).coerceAtMost(1f)
                    scaleX = lerp(1f, 0.85f, off); scaleY = scaleX
                    alpha = lerp(1f, 0.5f, off)
                }
                .clickable(role = Role.Tab) { scope.launch { pager.animateScrollToPage(page) } }
                // the preview is the real skin: keep its play/seek accessibility actions out
                .clearAndSetSemantics {
                    contentDescription = "${label(item)} look"
                    selected = page == pager.currentPage
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                preview(item, DpSize(maxWidth, maxHeight))
                // on top, so touches skip the skin's own play/scratch/seek gestures and reach the pager
                Box(Modifier.matchParentSize().pointerInput(Unit) {})
            }
            Text(label(item), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
    }
    var customize by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // the picker is generic; only the visualizers have a shared look to customize
        if (items.firstOrNull() is Visualizer) OutlinedButton(onClick = { customize = true }) { Text("Customize") }
        Button(onClick = { onSelect(items[pager.currentPage]) }) { Text("Select") }
    }
    if (customize) {
        val item = items[pager.currentPage]
        VisualizerCustomizeSheet(preview = { preview(item, DpSize(200.dp, 200.dp)) }, onDismiss = { customize = false })
    }
}
