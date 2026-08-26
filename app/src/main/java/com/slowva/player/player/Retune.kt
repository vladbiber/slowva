package com.slowva.player.player

/**
 * The "magic Hz" retune presets, carried over from spotc.
 *
 * Each preset retunes the nearest equal-temperament note onto the advertised
 * frequency, which is what real solfeggio converters do — jumping A4 straight
 * to 174 or 963 Hz would mean playing at 0.4x or 2.2x. The "deep" presets do
 * drag A4 itself, so they sound slowed as well, and bring their own reverb
 * (reverb never shifts pitch, so the Hz stays exact).
 *
 * The claims attached to these frequencies are folklore. Nothing in the
 * research literature supports them; they are here because they sound
 * different, not because they do anything to your cells.
 */
data class RetunePreset(
    val hz: Int,
    val note: String,
    val ratio: Float,
    val reverb: Float,
    val claim: String,
)

val RETUNE_PRESETS = listOf(
    RetunePreset(440, "A4", 1.0f, 0.0f, "standard concert pitch (retune off)"),
    RetunePreset(174, "F3", 0.996483f, 0.0f, "claimed: natural anaesthetic, pain relief"),
    RetunePreset(285, "C#4", 1.028204f, 0.0f, "claimed: tissue regeneration"),
    RetunePreset(396, "G4", 1.010216f, 0.0f, "claimed: liberation from fear"),
    RetunePreset(432, "A4", 0.981818f, 0.0f, "claimed: natural tuning, relaxation"),
    RetunePreset(528, "C5", 1.009075f, 0.0f, "claimed: love frequency, DNA repair"),
    RetunePreset(963, "B5", 0.974927f, 0.0f, "claimed: pineal gland, third eye"),
    RetunePreset(417, "A4", 0.947727f, 0.20f, "claimed: undoing trauma (deep)"),
    RetunePreset(396, "A4", 0.9f, 0.30f, "claimed: liberation from fear (deep)"),
    RetunePreset(369, "F#4", 0.997314f, 0.0f, "claimed: Tesla's 3-6-9 key to the universe"),
)

/** The 8 gradual slowed+reverb steps spotc cycles through. */
val SLOW_STEPS = listOf(
    1.00f to 0.00f,
    0.95f to 0.10f,
    0.92f to 0.18f,
    0.88f to 0.25f,
    0.85f to 0.32f,
    0.82f to 0.40f,
    0.78f to 0.50f,
    0.75f to 0.60f,
)
