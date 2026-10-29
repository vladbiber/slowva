package com.slowva.player.ui.skins.radio

import androidx.compose.ui.graphics.Color

// Deep enough that a big white letter stays readable on every one.
private val StationPalette = listOf(
    Color(0xFFD32F2F), Color(0xFFC2185B), Color(0xFF7B1FA2), Color(0xFF512DA8),
    Color(0xFF303F9F), Color(0xFF1976D2), Color(0xFF0277BD), Color(0xFF00838F),
    Color(0xFF00796B), Color(0xFF2E7D32), Color(0xFF827717), Color(0xFFD84315),
)

/** The color a radio face wears for a name without a picture, always the same for the same name. */
fun stationColor(name: String): Color =
    StationPalette[Math.floorMod(name.trim().lowercase().hashCode(), StationPalette.size)]
