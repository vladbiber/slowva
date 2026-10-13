package com.slowva.player.player

/** One-tap starting points that set every effect at once. */
enum class Mood(
    val label: String,
    val rate: Float,
    val reverb: Float,
    val bassDb: Float = 0f,
    val width: Float = 1f,
    val pan8d: Float = 0f,
    val muffle: Float = 0f,
    val vinyl: VinylPreset? = null,
) {
    ORIGINAL("Original", 1f, 0f),
    SLOWED("Slowed + reverb", 0.85f, 0.35f, bassDb = 2f),
    DAYCORE("Daycore", 0.78f, 0.5f, bassDb = 4f, width = 1.2f),
    NIGHTCORE("Nightcore", 1.2f, 0.05f, width = 1.1f),
    LOFI("Lo-fi record", 0.92f, 0.15f, bassDb = 3f, width = 0.8f, muffle = 0.25f, vinyl = VinylPreset.LOFI),
    ROOM("Another room", 0.95f, 0.3f, bassDb = 3f, muffle = 0.75f),
    EIGHT_D("8D", 0.9f, 0.3f, pan8d = 0.85f),
}
