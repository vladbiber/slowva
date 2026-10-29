package com.slowva.player.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slowva.player.lyrics.Lrc
import com.slowva.player.lyrics.Lyrics
import com.slowva.player.ui.skins.LiveProgress

/** Synced lyrics follow the song and a tap seeks to that line; plain lyrics just scroll. */
@Composable
fun LyricsView(lyrics: Lyrics, live: LiveProgress, ink: Color, dim: Color, modifier: Modifier = Modifier) {
    val lines = lyrics.synced
    if (lines.isNullOrEmpty()) {
        Text(
            lyrics.plain.orEmpty(), color = ink, style = MaterialTheme.typography.bodyLarge,
            modifier = modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        )
        return
    }
    val current by remember(lines) { derivedStateOf { Lrc.indexAt(lines, live.positionMs) } }
    val list = rememberLazyListState()
    // keep the sung line in the upper third
    LaunchedEffect(current) { list.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    LazyColumn(modifier, state = list, contentPadding = PaddingValues(vertical = 12.dp)) {
        itemsIndexed(lines) { i, line ->
            val now = i == current
            Text(
                line.text.ifEmpty { "♪" },
                color = if (now) ink else dim,
                style = if (now) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "play from this line") { live.seekTo(line.timeMs) }
                    .semantics { if (now) stateDescription = "now singing" }
                    .padding(vertical = 6.dp),
            )
        }
    }
}
