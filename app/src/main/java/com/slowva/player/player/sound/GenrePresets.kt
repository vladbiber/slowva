package com.slowva.player.player.sound

import android.content.Context
import com.slowva.player.data.db.GenrePresetEntity
import com.slowva.player.genre.Genre
import com.slowva.player.player.fx.EqSpec
import org.json.JSONObject

/**
 * One genre tone: a subtle ten band curve plus a few extras. [key] is a [Genre] name,
 * or [BALANCED]. [edited] marks the user's own copy.
 */
data class GenrePreset(
    val key: String,
    val label: String,
    val bands: List<Float>,
    val highpassHz: Float = 0f,
    val loudness: Boolean = false,
    val tape: Float = 0f,
    val compress: Float = 0f,
    val edited: Boolean = false,
) {
    val isFlat: Boolean get() = bands.all { kotlin.math.abs(it) < 0.05f } && highpassHz <= 0f

    fun spec(fadeMs: Int): EqSpec? = if (isFlat) null else EqSpec.graphic(bands.toFloatArray(), highpassHz, fadeMs)

    fun toEntity(now: Long) = GenrePresetEntity(
        presetKey = key, bands = bands.joinToString(","), highpassHz = highpassHz,
        loudness = loudness, tape = tape, compress = compress, at = now,
    )

    companion object {
        const val BALANCED = "BALANCED"

        fun labelOf(key: String): String = if (key == BALANCED) "Balanced" else runCatching { Genre.valueOf(key).label }.getOrDefault(key)
    }
}

/** Bundled presets from assets/presets/genres.json, with the user's copies laid over them. */
object GenrePresets {
    @Volatile private var bundled: Map<String, GenrePreset> = emptyMap()

    fun load(context: Context) {
        if (bundled.isNotEmpty()) return
        bundled = runCatching {
            val text = context.assets.open("presets/genres.json").bufferedReader().use { it.readText() }
            parse(text)
        }.getOrDefault(mapOf(BALANCED_PRESET.key to BALANCED_PRESET))
    }

    fun bundled(key: String): GenrePreset? = bundled[key]

    /** Every preset in display order: Balanced first, then the [Genre] order. */
    fun merged(user: Map<String, GenrePreset>): List<GenrePreset> {
        val keys = listOf(GenrePreset.BALANCED) + Genre.entries.map { it.name }
        return keys.mapNotNull { user[it] ?: bundled[it] }
    }

    fun parse(text: String): Map<String, GenrePreset> {
        val root = JSONObject(text).getJSONArray("presets")
        val out = LinkedHashMap<String, GenrePreset>()
        for (i in 0 until root.length()) {
            val o = root.getJSONObject(i)
            val key = o.getString("key")
            if (key != GenrePreset.BALANCED && runCatching { Genre.valueOf(key) }.isFailure) continue
            val arr = o.getJSONArray("bands")
            val bands = List(10) { j -> if (j < arr.length()) arr.getDouble(j).toFloat().coerceIn(-12f, 12f) else 0f }
            out[key] = GenrePreset(
                key = key,
                label = GenrePreset.labelOf(key),
                bands = bands,
                highpassHz = o.optDouble("highpass", 0.0).toFloat(),
                loudness = o.optBoolean("loudness", false),
                tape = o.optDouble("tape", 0.0).toFloat(),
                compress = o.optDouble("compress", 0.0).toFloat(),
            )
        }
        return out
    }

    fun fromEntity(e: GenrePresetEntity): GenrePreset? {
        val bands = e.bands.split(',').mapNotNull { it.trim().toFloatOrNull() }
        if (bands.size != 10) return null
        return GenrePreset(e.presetKey, GenrePreset.labelOf(e.presetKey), bands, e.highpassHz, e.loudness, e.tape, e.compress, edited = true)
    }

    private val BALANCED_PRESET = GenrePreset(GenrePreset.BALANCED, "Balanced", List(10) { 0f })
}
