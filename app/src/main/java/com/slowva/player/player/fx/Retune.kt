package com.slowva.player.player.fx

/**
 * "Magic Hz" presets: the nearest note is retuned onto the target frequency.
 * The health claims attached to these numbers have no research behind them.
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
