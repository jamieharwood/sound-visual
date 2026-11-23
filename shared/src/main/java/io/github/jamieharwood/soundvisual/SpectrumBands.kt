package io.github.jamieharwood.soundvisual

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Turns a block of PCM samples into [bandCount] log-spaced frequency bands,
 * each expressed in dBFS and as a normalised 0..1 level for display.
 */
class SpectrumBands(
    val sampleRate: Int,
    val fftSize: Int,
    val bandCount: Int,
    val minHz: Float = 30f,
    maxHz: Float = 16_000f,
    val floorDb: Float = -90f,
    val ceilDb: Float = -10f,
) {
    val maxHz: Float = minOf(maxHz, sampleRate / 2f)

    private val fft = Fft(fftSize)
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2.0 * PI * it / (fftSize - 1))).toFloat() }
    private val binHz = sampleRate.toFloat() / fftSize

    /** Inclusive start / exclusive end FFT bin for each band. */
    private val bandStart = IntArray(bandCount)
    private val bandEnd = IntArray(bandCount)

    /** Magnitude in dBFS for each band from the most recent [process] call. */
    val bandDb = FloatArray(bandCount)

    /** Frequency (Hz) of the strongest bin from the most recent [process] call. */
    var peakHz = 0f
        private set

    /** Overall RMS level (dBFS) of the most recent block. */
    var rmsDb = floorDb
        private set

    init {
        val nyquistBin = fftSize / 2
        for (b in 0 until bandCount) {
            val lo = bandEdgeHz(b)
            val hi = bandEdgeHz(b + 1)
            val start = (lo / binHz).toInt().coerceIn(1, nyquistBin - 1)
            val end = (hi / binHz).toInt().coerceIn(start + 1, nyquistBin)
            bandStart[b] = start
            bandEnd[b] = end
        }
    }

    /** Lower edge of band [b] in Hz (b == bandCount gives the top edge). */
    fun bandEdgeHz(b: Int): Float = minHz * (maxHz / minHz).pow(b.toFloat() / bandCount)

    /** Fractional band position of [hz], where 0 is the left edge of band 0. */
    fun bandPosition(hz: Float): Float = bandCount * (ln(hz / minHz) / ln(maxHz / minHz))

    fun dbToLevel(db: Float): Float = ((db - floorDb) / (ceilDb - floorDb)).coerceIn(0f, 1f)

    /** Analyse [samples] (length [fftSize], range -1..1). Results land in [bandDb], [peakHz], [rmsDb]. */
    fun process(samples: FloatArray) {
        var sumSq = 0.0
        for (i in 0 until fftSize) {
            val s = samples[i]
            sumSq += s * s
            re[i] = s * window[i]
            im[i] = 0f
        }
        rmsDb = (20 * log10(sqrt(sumSq / fftSize) + 1e-9)).toFloat().coerceAtLeast(floorDb)

        fft.forward(re, im)

        // A full-scale sine through a Hann window peaks at fftSize / 4.
        val scale = 4f / fftSize
        var peakBin = 1
        var peakMag = 0f
        for (k in 1 until fftSize / 2) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k]) * scale
            re[k] = mag // reuse as magnitude buffer
            if (mag > peakMag) { peakMag = mag; peakBin = k }
        }
        peakHz = peakBin * binHz

        for (b in 0 until bandCount) {
            var m = 0f
            for (k in bandStart[b] until bandEnd[b]) if (re[k] > m) m = re[k]
            bandDb[b] = (20 * log10(m + 1e-9f)).coerceAtLeast(floorDb)
        }
    }
}
