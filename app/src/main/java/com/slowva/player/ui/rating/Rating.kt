package com.slowva.player.ui.rating

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slowva.player.data.RateTarget
import com.slowva.player.data.Ratings
import com.slowva.player.data.Track
import com.slowva.player.data.db.RatingKind
import com.slowva.player.data.starsLabel
import com.slowva.player.genre.Genre
import com.slowva.player.ui.AlbumArt
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.SurfaceDark
import com.slowva.player.ui.theme.TextMuted
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Five stars in half steps: [value] 0..10 (0 = not rated). Tap or drag to set; tapping the current value clears it.
 * TalkBack reads "3.5 stars" and can adjust it like a slider.
 */
@Composable
fun RatingBar(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, starSize: Dp = 32.dp, label: String = "Rating") {
    var live by remember(value) { mutableIntStateOf(value) }
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val width = starSize * 5
    fun at(x: Float, w: Int) = ceil(x / w * 10).toInt().coerceIn(1, 10)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .width(width)
            .pointerInput(Unit) {
                detectTapGestures { o -> val v = at(o.x, size.width); change(if (v == current) 0 else v) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> live = at(o.x, size.width) },
                    onDragEnd = { change(live) },
                    onDragCancel = { live = current },
                ) { c, _ -> live = at(c.position.x, size.width) }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = if (live == 0) "not rated" else starsLabel(live)
                progressBarRangeInfo = ProgressBarRangeInfo(live / 2f, 0f..5f, steps = 9)
                setProgress { target -> change((target * 2).roundToInt().coerceIn(0, 10)); true }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 1..5) {
            val icon = when {
                live >= i * 2 -> Icons.Filled.Star
                live == i * 2 - 1 -> Icons.AutoMirrored.Filled.StarHalf
                else -> Icons.Filled.StarBorder
            }
            Icon(icon, contentDescription = null, tint = if (live >= i * 2 - 1) Accent else TextMuted, modifier = Modifier.size(starSize))
        }
    }
}

/** Rates [track], its album and its artist, with a review and personal genre tags for one of them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RateSheet(track: Track, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val ratings = remember { Ratings.get(ctx) }
    val targets = remember(track) { listOfNotNull(RateTarget.song(track), RateTarget.album(track), RateTarget.artist(track.artist)) }
    var selected by remember(track) { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceDark) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(track, 56.dp, shape = MaterialTheme.shapes.small)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Rate", color = TextPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text("${track.title} · ${track.artist}", color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
            targets.forEach { t -> RateRow(ratings, t) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEachIndexed { i, t ->
                    FilterChip(selected = selected == i, onClick = { selected = i }, label = { Text(kindName(t.kind)) })
                }
            }
            targets.getOrNull(selected)?.let { ReviewAndTags(ratings, it) }
        }
    }
}

private fun kindName(k: RatingKind) = when (k) { RatingKind.SONG -> "Song"; RatingKind.ALBUM -> "Album"; RatingKind.ARTIST -> "Artist" }

@Composable
private fun RateRow(ratings: Ratings, t: RateTarget) {
    val row by remember(t.key) { ratings.rating(t.kind, t.key) }.collectAsState(null)
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(kindName(t.kind), color = TextMuted, style = MaterialTheme.typography.labelSmall)
            Text(t.title, color = TextPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RatingBar(row?.value ?: 0, { ratings.set(t, it) }, starSize = 28.dp, label = "${kindName(t.kind)} rating, ${t.title}")
    }
}

@Composable
private fun ReviewAndTags(ratings: Ratings, t: RateTarget) {
    val row by remember(t.key) { ratings.rating(t.kind, t.key) }.collectAsState(null)
    val tags by remember(t.key) { ratings.tags(t.kind, t.key) }.collectAsState(emptyList())
    var review by remember(t.key, row?.review) { mutableStateOf(row?.review.orEmpty()) }
    var newTag by remember(t.key) { mutableStateOf("") }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = review,
        onValueChange = { review = it.take(2000) },
        label = { Text("Review of this ${kindName(t.kind).lowercase()}") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        maxLines = 6,
    )
    if (review != row?.review.orEmpty()) {
        TextButton(onClick = { ratings.setReview(t, review) }) { Text("Save review") }
    }
    Spacer(Modifier.height(8.dp))
    Text("Your genre tags", color = TextSecondary, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { tag ->
            InputChip(
                selected = true,
                onClick = { ratings.removeTag(t.kind, t.key, tag) },
                label = { Text(tag) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove tag $tag", modifier = Modifier.size(18.dp)) },
            )
        }
    }
    val add = { ratings.addTag(t.kind, t.key, newTag); newTag = "" }
    OutlinedTextField(
        value = newTag,
        onValueChange = { newTag = it.take(40) },
        label = { Text("Add a tag") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { add() }),
        trailingIcon = { if (newTag.isNotBlank()) TextButton(onClick = add) { Text("Add") } },
        modifier = Modifier.fillMaxWidth(),
    )
    val suggestions = Genre.entries.filter { it != Genre.SPOKEN }.map { it.tags.first() }.filter { it !in tags }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        suggestions.forEach { s -> AssistChip(onClick = { ratings.addTag(t.kind, t.key, s) }, label = { Text(s) }) }
    }
}
