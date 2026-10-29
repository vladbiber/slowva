package com.slowva.player.ui.sound

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.slowva.player.player.sound.SoundEngine
import com.slowva.player.ui.BassSection
import com.slowva.player.ui.EffectRangeSection
import com.slowva.player.ui.RainSection
import com.slowva.player.ui.ResetFxButton
import com.slowva.player.ui.ReverbSection
import com.slowva.player.ui.ReverseSection
import com.slowva.player.ui.SectionHeader
import com.slowva.player.ui.SpaceSection
import com.slowva.player.ui.ModeSection
import com.slowva.player.ui.SpeedSection
import com.slowva.player.ui.TuningSection
import com.slowva.player.ui.VinylSection
import com.slowva.player.ui.enterStagger

/** Every sound setting: presets, speed, reverb, bass, EQ, then effects, output, the range and the mode last. */
@Composable
fun SoundTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    remember { SoundEngine.init(context) }
    val sections = listOf<@Composable () -> Unit>(
        { PresetStatus() },
        { GenrePresetsRow() },
        { SpeedSection() },
        { ReverbSection() },
        { BassSection() },
        { EqSection() },
        { Heading("Effects") },
        { TuningSection() },
        { SpaceSection() },
        { RainSection() },
        { VinylSection() },
        { ReverseSection() },
        { Heading("Output") },
        { HeadphonesSection() },
        { HifiSection() },
        { Heading("Range") },
        { EffectRangeSection() },
        { ResetFxButton() },
        // the modes change the whole app, so they sit last, under everything else
        { ModeSection() },
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // each section emits a single node, so the wrapper keeps the spacing as it was
        sections.forEachIndexed { i, section -> Box(Modifier.enterStagger(i)) { section() } }
    }
}

@Composable
private fun Heading(text: String) = SectionHeader(text, Modifier.padding(top = 12.dp, start = 4.dp))
