package za.munnik.tapoatv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recording starts at the first key frame: TapoStream.hasSps finds the H.264 SPS start code. */
class SpsTest {
    private fun b(vararg v: Int) = v.map { it.toByte() }.toByteArray()

    @Test fun findsSps() = assertTrue(TapoStream.hasSps(b(0x47, 0x40, 0, 0, 0, 1, 0x67, 0x4D)))
    @Test fun ignoresPFrame() = assertFalse(TapoStream.hasSps(b(0x47, 0, 0, 1, 0x41, 0x9A)))
    @Test fun shortPart() = assertFalse(TapoStream.hasSps(b(0, 0, 1)))
}
