package io.github.jamieharwood.soundvisual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SpectrumBandsTest {
    private val sampleRate = 44_100
    private val fftSize = 2048

    private fun sine(hz: Double, amplitude: Double = 1.0) =
        FloatArray(fftSize) { (amplitude * sin(2 * PI * hz * it / sampleRate)).toFloat() }

    @Test
    fun peakFrequencyMatchesInputTone() {
        val bands = SpectrumBands(sampleRate, fftSize, 64)
        bands.process(sine(1000.0))
        val binHz = sampleRate.toFloat() / fftSize
        assertTrue("peak ${bands.peakHz}", abs(bands.peakHz - 1000f) <= binHz)
    }

    @Test
    fun loudestBandContainsTone() {
        val bands = SpectrumBands(sampleRate, fftSize, 64)
        bands.process(sine(2500.0))
        val loudest = bands.bandDb.indices.maxBy { bands.bandDb[it] }
        assertTrue(bands.bandEdgeHz(loudest) <= 2500f + 25f)
        assertTrue(bands.bandEdgeHz(loudest + 1) >= 2500f - 25f)
    }

    @Test
    fun fullScaleSineIsAboutZeroDbfs() {
        val bands = SpectrumBands(sampleRate, fftSize, 64)
        bands.process(sine(sampleRate.toDouble() / fftSize * 100)) // exactly on a bin
        assertEquals(0f, bands.bandDb.max(), 1.0f)
    }

    @Test
    fun quieterToneIsLowerLevel() {
        val bands = SpectrumBands(sampleRate, fftSize, 64)
        bands.process(sine(440.0, 0.01))
        val db = bands.bandDb.max()
        assertEquals(-40f, db, 2f)
        assertEquals((db - bands.floorDb) / (bands.ceilDb - bands.floorDb), bands.dbToLevel(db), 1e-4f)
    }

    @Test
    fun silenceSitsAtFloor() {
        val bands = SpectrumBands(sampleRate, fftSize, 64)
        bands.process(FloatArray(fftSize))
        assertTrue(bands.bandDb.all { it == bands.floorDb })
        assertEquals(0f, bands.dbToLevel(bands.bandDb[0]))
    }
}
