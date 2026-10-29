package com.slowva.player.ui.art

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.slowva.player.ui.theme.ArtistShape

/**
 * An artist picture: their photo ([photoUrl]), else a collage of their [coverUris], else a [GeneratedCover] of the name. Clipped to [shape].
 */
@Composable
fun ArtistArt(
    name: String,
    photoUrl: String?,
    coverUris: List<String>,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    shape: Shape = ArtistShape,
) {
    val photo = photoUrl
    Box(modifier.size(size).clip(shape).background(coverTones(name).paper)) {
        val fallback: @Composable () -> Unit = {
            if (coverUris.isNotEmpty()) CoverCollage(coverUris, Modifier.fillMaxSize(), name)
            else GeneratedCover(name, Modifier.fillMaxSize())
        }
        if (photo == null) fallback()
        else SubcomposeAsyncImage(
            photo, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            loading = { if (coverUris.isNotEmpty()) fallback() }, error = { fallback() },
        )
    }
}

/**
 * Up to four covers: one fills the box, two to four make a 2x2 grid (repeating to fill it).
 * Missing tiles show the generated cover of [name]. Fills [modifier]; the caller sizes and clips it.
 */
@Composable
fun CoverCollage(covers: List<String>, modifier: Modifier = Modifier, name: String = "") {
    val list = covers.distinct()
    if (list.size == 1) {
        SubcomposeAsyncImage(list[0], null, modifier, contentScale = ContentScale.Crop, error = { GeneratedCover(name, Modifier.fillMaxSize()) })
        return
    }
    Column(modifier.background(MaterialTheme.colorScheme.secondaryContainer)) {
        for (r in 0..1) Row(Modifier.weight(1f)) {
            for (c in 0..1) Box(Modifier.weight(1f).fillMaxSize()) {
                // two covers sit diagonally, three or more fill in reading order
                val uri = if (list.isEmpty()) null else list[(if (list.size == 2) r + c else r * 2 + c) % list.size]
                if (uri != null) SubcomposeAsyncImage(uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, error = { GeneratedCover(name, Modifier.fillMaxSize()) })
                else GeneratedCover(name, Modifier.fillMaxSize())
            }
        }
    }
}
