package com.slowva.player.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow

/** Where the app colors come from. */
enum class ThemeSource(val label: String) { WALLPAPER("Wallpaper"), SLOWVA_BLUE("Slowva Blue"), DUO("Two colors") }

/** The backdrop behind Home's header and the player; tie-dye needs Android 13 shaders, older phones get Fade. */
enum class GradientStyle(val label: String, val dyed: Boolean = true) {
    SPIRAL("Spiral"), BULLSEYE("Bullseye"), CRUMPLE("Crumple"), ICE("Ice dye"), CHEVRON("Chevron"),
    ALBUM("Album art", false), FADE("Fade", false), OFF("Off", false),
}

/** Where the tie-dye takes its three dyes from. */
enum class DyeColors(val label: String) { THEME("Theme colors"), RAINBOW("Rainbow"), COVER("Album cover") }

/** How strongly the dye shows. */
enum class DyeStrength(val label: String, val amount: Float) { LOW("Low", 0.55f), MEDIUM("Medium", 0.8f), HIGH("High", 1f) }

/** The app font; [res] null means the phone's own. */
enum class AppFont(val label: String, val res: Int?, val variable: Boolean = true) {
    SYSTEM("Phone default", null),
    MANROPE("Manrope", com.slowva.player.R.font.font_manrope),
    INTER("Inter", com.slowva.player.R.font.font_inter),
    SPACE_GROTESK("Space Grotesk", com.slowva.player.R.font.font_space_grotesk),
    DM_SERIF("DM Serif headings", com.slowva.player.R.font.font_dm_serif, variable = false),
}

/** The colors a user can pick for the two-color theme. */
enum class Swatch(val label: String, val color: Color) {
    RED("Red", Color(0xFFE53935)),
    ORANGE("Orange", Color(0xFFFB8C00)),
    AMBER("Amber", Color(0xFFFFB300)),
    YELLOW("Yellow", Color(0xFFFDD835)),
    LIME("Lime", Color(0xFFC0CA33)),
    GREEN("Green", Color(0xFF43A047)),
    TEAL("Teal", Color(0xFF00897B)),
    CYAN("Cyan", Color(0xFF00ACC1)),
    BLUE("Blue", Color(0xFF1E88E5)),
    INDIGO("Indigo", Color(0xFF3949AB)),
    PURPLE("Purple", Color(0xFF8E24AA)),
    PINK("Pink", Color(0xFFD81B60)),
}

/** Stored theme source, else the old "Wallpaper colors" switch (on = Wallpaper, off = Slowva Blue). */
internal fun storedSource(stored: String?, dynamicColors: Boolean): ThemeSource =
    runCatching { ThemeSource.valueOf(stored!!) }
        .getOrDefault(if (dynamicColors) ThemeSource.WALLPAPER else ThemeSource.SLOWVA_BLUE)

/** Appearance settings, kept next to the skin in the "slowva-ui" prefs. */
object Appearance {
    private lateinit var app: Context

    val source = MutableStateFlow(ThemeSource.WALLPAPER)
    val color1 = MutableStateFlow(Swatch.BLUE)
    val color2 = MutableStateFlow(Swatch.PURPLE)
    val gradient = MutableStateFlow(GradientStyle.SPIRAL)
    val dyeColors = MutableStateFlow(DyeColors.THEME)
    val dyeStrength = MutableStateFlow(DyeStrength.MEDIUM)
    val font = MutableStateFlow(AppFont.MANROPE)
    val pureBlack = MutableStateFlow(false)
    val coverColors = MutableStateFlow(false)
    /** Player background swells with the bass; battery saver turns it off whatever this says. */
    val reactToMusic = MutableStateFlow(true)

    val dynamicSupported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        source.value = storedSource(p.getString("theme_source", null), p.getBoolean("dynamic_colors", true))
        color1.value = runCatching { Swatch.valueOf(p.getString("color1", null)!!) }.getOrDefault(Swatch.BLUE)
        color2.value = runCatching { Swatch.valueOf(p.getString("color2", null)!!) }.getOrDefault(Swatch.PURPLE)
        // "gradient" held Oil/Liquid before 3.0; a new key so everyone starts on the tie-dye
        gradient.value = runCatching { GradientStyle.valueOf(p.getString("backdrop", null)!!) }.getOrDefault(GradientStyle.SPIRAL)
        dyeColors.value = runCatching { DyeColors.valueOf(p.getString("dye_colors", null)!!) }.getOrDefault(DyeColors.THEME)
        dyeStrength.value = runCatching { DyeStrength.valueOf(p.getString("dye_strength", null)!!) }.getOrDefault(DyeStrength.MEDIUM)
        font.value = runCatching { AppFont.valueOf(p.getString("font", null)!!) }.getOrDefault(AppFont.MANROPE)
        pureBlack.value = p.getBoolean("pure_black", false)
        coverColors.value = p.getBoolean("cover_colors", false)
        reactToMusic.value = p.getBoolean("react_to_music", true)
    }

    fun setSource(v: ThemeSource) { source.value = v; save() }
    fun setColor1(v: Swatch) { color1.value = v; save() }
    fun setColor2(v: Swatch) { color2.value = v; save() }
    fun setGradient(v: GradientStyle) { gradient.value = v; save() }
    fun setDyeColors(v: DyeColors) { dyeColors.value = v; save() }
    fun setDyeStrength(v: DyeStrength) { dyeStrength.value = v; save() }
    fun setFont(v: AppFont) { font.value = v; save() }
    fun setPureBlack(v: Boolean) { pureBlack.value = v; save() }
    fun setCoverColors(v: Boolean) { coverColors.value = v; save() }
    fun setReactToMusic(v: Boolean) { reactToMusic.value = v; save() }

    private fun save() {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("theme_source", source.value.name)
            .putString("color1", color1.value.name)
            .putString("color2", color2.value.name)
            .putString("backdrop", gradient.value.name)
            .putString("dye_colors", dyeColors.value.name)
            .putString("dye_strength", dyeStrength.value.name)
            .putString("font", font.value.name)
            .putBoolean("pure_black", pureBlack.value)
            .putBoolean("cover_colors", coverColors.value)
            .putBoolean("react_to_music", reactToMusic.value)
            .apply()
    }

    private const val PREFS = "slowva-ui"
}
