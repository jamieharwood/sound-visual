package io.github.jamieharwood.soundvisual

/**
 * Messages between the phone app and the Wear OS companion over the Wearable Data Layer.
 *
 * The watch sends [PATH_SUBSCRIBE] every couple of seconds while it's on screen (and
 * [PATH_UNSUBSCRIBE] when it leaves); the phone streams [PATH_ROWS] only to watches it has
 * heard from recently, so nothing is sent while nobody is looking.
 *
 * A rows message is a small binary batch:
 *   [0] version, [1] flags (bit 0 = stereo), [2] palette ordinal, [3] row count,
 *   [4..5] rows per second × 10 (big-endian), then for each row (oldest first)
 *   [BANDS] left-channel levels then [BANDS] right-channel levels, each 0..255.
 */
object WearProtocol {
    const val PATH_SUBSCRIBE = "/soundvisual/subscribe"
    const val PATH_UNSUBSCRIBE = "/soundvisual/unsubscribe"
    const val PATH_ROWS = "/soundvisual/rows"

    const val VERSION: Byte = 1
    /** The watch draws a 32-band grid; the phone merges its 64 bands in pairs. */
    const val BANDS = 32
    const val HEADER = 6
    const val ROW_BYTES = 2 * BANDS

    class Batch(
        val stereo: Boolean,
        val palette: Palette,
        val rowsPerSecond: Float,
        /** rowCount × ROW_BYTES, oldest row first. */
        val rows: ByteArray,
        val rowCount: Int,
    )

    fun encode(stereo: Boolean, palette: Palette, rowsPerSecond: Float, rows: ByteArray, rowCount: Int): ByteArray {
        val out = ByteArray(HEADER + rowCount * ROW_BYTES)
        out[0] = VERSION
        out[1] = if (stereo) 1 else 0
        out[2] = palette.ordinal.toByte()
        out[3] = rowCount.toByte()
        val rps = (rowsPerSecond * 10).toInt().coerceIn(0, 0xFFFF)
        out[4] = (rps shr 8).toByte()
        out[5] = rps.toByte()
        System.arraycopy(rows, 0, out, HEADER, rowCount * ROW_BYTES)
        return out
    }

    /** Returns null for messages from an incompatible version or that are malformed. */
    fun decode(data: ByteArray): Batch? {
        if (data.size < HEADER || data[0] != VERSION) return null
        val rowCount = data[3].toInt() and 0xFF
        if (data.size != HEADER + rowCount * ROW_BYTES) return null
        val rps = ((data[4].toInt() and 0xFF) shl 8 or (data[5].toInt() and 0xFF)) / 10f
        val palette = Palette.entries.getOrElse(data[2].toInt() and 0xFF) { Palette.SPECTRUM }
        return Batch(
            stereo = (data[1].toInt() and 1) != 0,
            palette = palette,
            rowsPerSecond = rps,
            rows = data.copyOfRange(HEADER, data.size),
            rowCount = rowCount,
        )
    }

    /** 0..1 level → byte. */
    fun levelToByte(level: Float): Byte = (level.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()

    /** byte → 0..1 level. */
    fun byteToLevel(b: Byte): Float = (b.toInt() and 0xFF) / 255f
}
