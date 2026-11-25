package io.github.jamieharwood.soundvisual

/**
 * What [GridRenderer] draws: a rolling history of per-band levels for two channels.
 * Implemented by the phone's audio analyzer and by the watch's copy of the phone's data.
 */
interface SpectrumFeed {
    /** Frequency bands across the grid (log-spaced from [minHz] to [maxHz]). */
    val bandCount: Int
    /** Rows of history, newest at the front. */
    val historyRows: Int
    val minHz: Float
    val maxHz: Float

    /** Fractional band position of [hz], where 0 is the left edge of band 0. */
    fun bandPosition(hz: Float): Float

    /** dBFS mapped to the bottom / top of a bar, and the gain applied, for the axis labels. */
    val floorDb: Float
    val ceilDb: Float
    val effectiveGainDb: Float

    /** Seconds of audio covered by the history. */
    val historySeconds: Float

    /**
     * Copy the history into [out] (size 2 * bandCount * historyRows), newest row first: the
     * left channel's rows, then the right channel's. Levels are 0..1 bar heights.
     */
    fun snapshot(out: FloatArray)
}
