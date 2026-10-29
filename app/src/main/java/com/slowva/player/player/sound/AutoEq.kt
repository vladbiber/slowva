package com.slowva.player.player.sound

import com.slowva.player.player.fx.EqBand
import com.slowva.player.player.fx.FilterType

/** A parsed AutoEQ ParametricEQ.txt. */
data class AutoEqProfile(val preampDb: Float, val bands: List<EqBand>)

/** Reads AutoEQ / Equalizer APO parametric files; anything else is refused with a reason. */
object AutoEq {
    const val MAX_BYTES = 64 * 1024
    const val MAX_FILTERS = 10

    private val PREAMP = Regex("""^preamp:\s*([-+]?\d+(?:\.\d+)?)\s*db$""", RegexOption.IGNORE_CASE)
    private val FILTER = Regex(
        """^filter\s*\d*\s*:\s*(on|off)\s+([a-z]+)(?:\s+\d+\s*db)?\s+fc\s+(\d+(?:\.\d+)?)\s*hz""" +
            """(?:\s+gain\s+([-+]?\d+(?:\.\d+)?)\s*db)?(?:\s+q\s+(\d+(?:\.\d+)?))?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun typeOf(code: String): FilterType? = when (code.uppercase()) {
        "PK", "PEQ", "PEAK" -> FilterType.PEAK
        "LSC", "LS", "LSQ" -> FilterType.LOW_SHELF
        "HSC", "HS", "HSQ" -> FilterType.HIGH_SHELF
        "LP", "LPQ" -> FilterType.LOW_PASS
        "HP", "HPQ" -> FilterType.HIGH_PASS
        else -> null
    }

    fun parse(text: String): Result<AutoEqProfile> = runCatching {
        require(text.length <= MAX_BYTES) { "That file is too large for an AutoEQ profile" }
        require(text.none { it == '\u0000' }) { "That is not a text file. Pick the ParametricEQ.txt from AutoEQ" }
        var preamp = 0f
        val bands = ArrayList<EqBand>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachIndexed
            PREAMP.find(line)?.let { m ->
                preamp = m.groupValues[1].toFloat()
                require(preamp in -40f..20f) { "Line ${index + 1}: preamp $preamp dB is out of range" }
                return@forEachIndexed
            }
            val m = FILTER.find(line)
            require(m != null) { "Line ${index + 1} is not an AutoEQ line: \"${line.take(40)}\"" }
            if (m.groupValues[1].equals("off", ignoreCase = true)) return@forEachIndexed
            val type = typeOf(m.groupValues[2]) ?: error("Line ${index + 1}: unknown filter type ${m.groupValues[2]}")
            val fc = m.groupValues[3].toFloat()
            val gain = m.groupValues[4].toFloatOrNull() ?: 0f
            val q = m.groupValues[5].toFloatOrNull() ?: 0.707f
            require(fc in 10f..24000f) { "Line ${index + 1}: frequency $fc Hz is out of range" }
            require(gain in -30f..30f) { "Line ${index + 1}: gain $gain dB is out of range" }
            require(q in 0.05f..20f) { "Line ${index + 1}: Q $q is out of range" }
            require(!type.hasGain || m.groupValues[4].isNotEmpty()) { "Line ${index + 1}: ${type.label} needs a gain" }
            bands += EqBand(type, fc, gain, q)
        }
        require(bands.isNotEmpty()) { "No filters found. Pick the ParametricEQ.txt for your headphones from AutoEQ" }
        require(bands.size <= MAX_FILTERS) { "${bands.size} filters, at most $MAX_FILTERS are supported" }
        AutoEqProfile(preamp, bands)
    }

    /** Compact storage form, "PK 105 -2.3 0.7;LSC 105 5.5 0.7". */
    fun encode(bands: List<EqBand>): String = bands.joinToString(";") { "${it.type.code} ${it.freq} ${it.gainDb} ${it.q}" }

    fun decode(s: String): List<EqBand> = s.split(';').mapNotNull { part ->
        val p = part.trim().split(' ')
        if (p.size != 4) return@mapNotNull null
        val type = FilterType.entries.firstOrNull { it.code == p[0] } ?: return@mapNotNull null
        val f = p[1].toFloatOrNull() ?: return@mapNotNull null
        val g = p[2].toFloatOrNull() ?: return@mapNotNull null
        val q = p[3].toFloatOrNull() ?: return@mapNotNull null
        EqBand(type, f.coerceIn(10f, 24000f), g.coerceIn(-30f, 30f), q.coerceIn(0.05f, 20f))
    }.take(MAX_FILTERS)
}
