package io.github.jamieharwood.soundvisual

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearProtocolTest {
    @Test
    fun roundTripsABatch() {
        val rows = ByteArray(3 * WearProtocol.ROW_BYTES) { it.toByte() }
        val data = WearProtocol.encode(true, Palette.NEON, 21.5f, rows, 3)
        val batch = WearProtocol.decode(data)!!
        assertTrue(batch.stereo)
        assertEquals(Palette.NEON, batch.palette)
        assertEquals(21.5f, batch.rowsPerSecond, 0.05f)
        assertEquals(3, batch.rowCount)
        assertArrayEquals(rows, batch.rows)
    }

    @Test
    fun rejectsWrongVersionAndBadLength() {
        val data = WearProtocol.encode(false, Palette.MONO, 20f, ByteArray(WearProtocol.ROW_BYTES), 1)
        assertNull(WearProtocol.decode(data.copyOf(data.size - 1)))
        data[0] = 99
        assertNull(WearProtocol.decode(data))
    }

    @Test
    fun levelBytesCoverTheRange() {
        assertEquals(0f, WearProtocol.byteToLevel(WearProtocol.levelToByte(-1f)), 0f)
        assertEquals(1f, WearProtocol.byteToLevel(WearProtocol.levelToByte(2f)), 0f)
        assertEquals(0.5f, WearProtocol.byteToLevel(WearProtocol.levelToByte(0.5f)), 0.003f)
    }
}
