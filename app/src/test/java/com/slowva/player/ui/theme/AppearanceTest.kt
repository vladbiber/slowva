package com.slowva.player.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceTest {
    @Test
    fun oldWallpaperSwitchMigrates() {
        assertEquals(ThemeSource.WALLPAPER, storedSource(null, dynamicColors = true))
        assertEquals(ThemeSource.SLOWVA_BLUE, storedSource(null, dynamicColors = false))
    }

    @Test
    fun storedSourceWinsAndJunkFallsBack() {
        assertEquals(ThemeSource.DUO, storedSource("DUO", dynamicColors = true))
        assertEquals(ThemeSource.SLOWVA_BLUE, storedSource("NOPE", dynamicColors = false))
    }
}
