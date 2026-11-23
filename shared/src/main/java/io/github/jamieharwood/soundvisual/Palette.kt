package io.github.jamieharwood.soundvisual

import android.graphics.Color
import kotlin.math.abs

/** Colour schemes for the bars: level 0 (quiet) maps to the first colour, 1 (loud) to the last. */
enum class Palette(val label: String, private val keys: List<FloatArray>?) {
    SPECTRUM("Spectrum", null), // classic blue → cyan → green → yellow → red
    FIRE("Fire", rgb(0x260000, 0x990000, 0xFF5A00, 0xFFCC1A, 0xFFFFD9)),
    OCEAN("Ocean", rgb(0x000D40, 0x004D99, 0x00A6CC, 0x66E6E6, 0xE6FFFF)),
    NEON("Neon", rgb(0x26005A, 0x8000CC, 0xF21ACC, 0x4DCCFF, 0xD9FFFF)),
    MATRIX("Matrix", rgb(0x001F00, 0x00660D, 0x1ABF26, 0x66FF66, 0xD9FFD9)),
    SUNSET("Sunset", rgb(0x1A004D, 0x800080, 0xE63366, 0xFF8C33, 0xFFE680)),
    MONO("Mono", rgb(0x262626, 0x666666, 0xA6A6A6, 0xD9D9D9, 0xFFFFFF));

    /** [STOPS] evenly spaced RGB colours (0..1), flattened for the bar shader's `uStops`. */
    val stops: FloatArray by lazy {
        FloatArray(STOPS * 3).also { out ->
            for (i in 0 until STOPS) colorAt(i / (STOPS - 1f)).copyInto(out, i * 3)
        }
    }

    /** ARGB colours for a gradient preview swatch. */
    val previewColors: IntArray by lazy {
        IntArray(STOPS) { i -> stops.let { Color.rgb(it[i * 3], it[i * 3 + 1], it[i * 3 + 2]) } }
    }

    private fun colorAt(t: Float): FloatArray {
        if (keys == null) {
            // Same "jet" ramp the app has always used, starting just above pure blue.
            val x = 0.08f + 0.92f * t
            fun ch(c: Float) = (1.5f - abs(4f * x - c)).coerceIn(0f, 1f)
            return floatArrayOf(ch(3f), ch(2f), ch(1f))
        }
        val p = t * (keys.size - 1)
        val i = p.toInt().coerceAtMost(keys.size - 2)
        val f = p - i
        val a = keys[i]
        val b = keys[i + 1]
        return FloatArray(3) { a[it] + (b[it] - a[it]) * f }
    }

    companion object {
        const val STOPS = 8

        fun fromName(name: String?): Palette = entries.firstOrNull { it.name == name } ?: SPECTRUM
    }
}

private fun rgb(vararg hex: Int): List<FloatArray> = hex.map {
    floatArrayOf(((it shr 16) and 0xFF) / 255f, ((it shr 8) and 0xFF) / 255f, (it and 0xFF) / 255f)
}
