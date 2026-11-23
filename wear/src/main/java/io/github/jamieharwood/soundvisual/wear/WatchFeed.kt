package io.github.jamieharwood.soundvisual.wear

import android.os.SystemClock
import io.github.jamieharwood.soundvisual.Palette
import io.github.jamieharwood.soundvisual.SpectrumFeed
import io.github.jamieharwood.soundvisual.WearProtocol
import kotlin.math.ln

/** The watch's copy of the phone's graph: a ring of rows filled from [WearProtocol] batches. */
class WatchFeed : SpectrumFeed {
    override val bandCount = WearProtocol.BANDS
    override val historyRows = 32
    // Same band layout as the phone (SpectrumBands defaults), just half as many bands.
    override val minHz = 30f
    override val maxHz = 16_000f
    override fun bandPosition(hz: Float) = bandCount * (ln(hz / minHz) / ln(maxHz / minHz))
    // The phone has already applied its level and scale; labels aren't shown on the watch.
    override val floorDb = -90f
    override val ceilDb = -10f
    override val effectiveGainDb = 0f
    @Volatile private var rowsPerSecond = 20f
    override val historySeconds: Float get() = historyRows / rowsPerSecond

    @Volatile var stereo = false; private set
    @Volatile var palette: Palette = Palette.SPECTRUM; private set
    /** [SystemClock.elapsedRealtime] of the last batch from the phone (0 = never). */
    @Volatile var lastDataAtMs = 0L; private set

    private val lock = Any()
    private val ringLeft = FloatArray(bandCount * historyRows)
    private val ringRight = FloatArray(bandCount * historyRows)
    private var head = 0

    fun add(batch: WearProtocol.Batch) {
        stereo = batch.stereo
        palette = batch.palette
        if (batch.rowsPerSecond > 0f) rowsPerSecond = batch.rowsPerSecond
        lastDataAtMs = SystemClock.elapsedRealtime()
        synchronized(lock) {
            for (r in 0 until batch.rowCount) {
                head = (head + 1) % historyRows
                val src = r * WearProtocol.ROW_BYTES
                val dst = head * bandCount
                for (i in 0 until bandCount) {
                    ringLeft[dst + i] = WearProtocol.byteToLevel(batch.rows[src + i])
                    ringRight[dst + i] = WearProtocol.byteToLevel(batch.rows[src + bandCount + i])
                }
            }
        }
    }

    override fun snapshot(out: FloatArray) {
        val perSide = bandCount * historyRows
        synchronized(lock) {
            for (r in 0 until historyRows) {
                val src = ((head - r + historyRows) % historyRows) * bandCount
                System.arraycopy(ringLeft, src, out, r * bandCount, bandCount)
                System.arraycopy(ringRight, src, out, perSide + r * bandCount, bandCount)
            }
        }
    }
}
