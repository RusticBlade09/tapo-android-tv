package za.munnik.tapoatv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** A tiny hand-made TS: PAT → PMT (video 0x1B on 0x100, Tapo sound 0x90 on 0x101) → one sound PES. */
class TsAudioTest {
    private fun packet(pid: Int, start: Boolean, payload: List<Int>): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47; p[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte(); p[2] = pid.toByte(); p[3] = 0x10
        payload.forEachIndexed { i, v -> p[4 + i] = v.toByte() }
        return p
    }

    private val pat = packet(0, true, listOf(0, 0x00, 0xB0, 13, 0, 1, 0xC1, 0, 0, 0, 1, 0xE0 + 0x10, 0x00, 0, 0, 0, 0))
    private val pmt = packet(0x1000, true, listOf(0, 0x02, 0xB0, 23, 0, 1, 0xC1, 0, 0, 0xE1, 0x00, 0xF0, 0,
        0x1B, 0xE1, 0x00, 0xF0, 0, 0x90, 0xE1, 0x01, 0xF0, 0, 0, 0, 0, 0))
    private val audio = packet(0x101, false, emptyList()).also { p ->
        val pes = listOf(0, 0, 1, 0xC0, 0, 6, 0x80, 0x80, 0, 0xD5, 0x55, 0x2A) // 9-byte PES header + 3 sound bytes
        p[1] = 0x41; p[3] = 0x30; p[4] = (188 - 5 - pes.size).toByte() // adaptation field stuffing fills the rest
        pes.forEachIndexed { i, v -> p[188 - pes.size + i] = v.toByte() }
    }

    @Test fun picksSoundBytes() {
        val got = mutableListOf<Byte>()
        val ts = TsAudio { b, off, len -> for (i in off until off + len) got += b[i] }
        val all = pat + pmt + audio
        ts.feed(all, 0, 100); ts.feed(all, 100, all.size - 100) // split across reads like a real stream
        assertArrayEquals(byteArrayOf(0xD5.toByte(), 0x55, 0x2A), got.toByteArray())
    }

    @Test fun alawDecode() {
        assertEquals(8, TsAudio.alaw(0xD5))
        assertEquals(-8, TsAudio.alaw(0x55))
        assertEquals(-32256, TsAudio.alaw(0x2A)) // loudest negative step
    }
}
