package com.slowva.player.ui.skins.radio

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

enum class RadioSkin(val label: String) {
    BOOMBOX("Boombox"), MINIDISC("MiniDisc"), CAR("Car radio");

    companion object {
        private const val PREFS = "slowva-ui"

        fun load(context: Context): RadioSkin = runCatching {
            valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("radio_skin", null)!!)
        }.getOrDefault(BOOMBOX)

        fun save(context: Context, skin: RadioSkin) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("radio_skin", skin.name).apply()
        }

        /** Whether the player shows a radio skin instead of a [com.slowva.player.ui.skins.Skin]. */
        fun isOn(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("radio_on", false)

        fun setOn(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("radio_on", on).apply()
        }
    }
}

/** width/height ratio the stage should give each skin */
fun RadioSkin.aspectRatio(): Float = when (this) {
    RadioSkin.BOOMBOX -> 1.6f
    RadioSkin.MINIDISC -> 1.2f
    RadioSkin.CAR -> 2.2f
}

private fun RadioSkin.playKey(): Rect = when (this) {
    RadioSkin.BOOMBOX -> BoomboxKey
    RadioSkin.MINIDISC -> MiniDiscKey
    RadioSkin.CAR -> CarKey
}

/**
 * A live-radio device face. Speakers, needle, disc and LCD meters move only
 * while [playing] (and never when [still]); the device's play key toggles.
 */
@Composable
fun RadioSkinView(
    skin: RadioSkin,
    stationName: String,
    liveTitle: String?,
    art: Painter?,
    accent: Color,
    playing: Boolean,
    still: Boolean,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val phase = if (playing && !still) {
        rememberInfiniteTransition(label = "radio")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing)), label = "phase")
    } else null
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val name = if (skin.label.endsWith("radio")) skin.label else "${skin.label} radio"
    val what = "$name, $stationName, " + if (playing) listOfNotNull("playing", liveTitle).joinToString(" ") else "paused"
    Box(modifier.fillMaxWidth().aspectRatio(skin.aspectRatio()).semantics { contentDescription = what }) {
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val scene = RadioScene(stationName, liveTitle, art, accent, playing, pressed, measurer) { phase?.value ?: 0f }
                when (skin) {
                    RadioSkin.BOOMBOX -> boombox(scene)
                    RadioSkin.MINIDISC -> miniDisc(scene)
                    RadioSkin.CAR -> carRadio(scene)
                }
            }
        )
        // the picker's previews are not controls
        if (!still) {
            val k = skin.playKey()
            Box(
                Modifier
                    .align(BiasAlignment(2 * k.left / (1 - k.width) - 1, 2 * k.top / (1 - k.height) - 1))
                    .fillMaxWidth(k.width)
                    .fillMaxHeight(k.height)
                    .clickable(interaction, indication = null, role = Role.Button, onClick = onTogglePlay)
                    .semantics { contentDescription = if (playing) "Pause" else "Play" }
            )
        }
    }
}

@Composable
private fun SamplePreview(skin: RadioSkin, playing: Boolean = true) = RadioSkinView(
    skin, "Radio Paradise", "Pink Floyd - Wish You Were Here (live at Knebworth 1990)", null,
    Color(0xFF5B8CFF), playing = playing, still = false, onTogglePlay = {}, modifier = Modifier.padding(16.dp),
)

@Preview(name = "Boombox", widthDp = 400, showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun BoomboxPreview() = SamplePreview(RadioSkin.BOOMBOX)

@Preview(name = "MiniDisc", widthDp = 400, showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun MiniDiscPreview() = SamplePreview(RadioSkin.MINIDISC)

@Preview(name = "Car radio", widthDp = 400, showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun CarRadioPreview() = SamplePreview(RadioSkin.CAR, playing = false)
