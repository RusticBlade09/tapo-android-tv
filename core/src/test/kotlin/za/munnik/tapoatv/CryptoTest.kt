package za.munnik.tapoatv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected values were computed with Python hashlib + pycryptodome (tools/.venv), the same maths go2rtc/pytapo use. */
class CryptoTest {
    private val md5u = "5D7845AC6EE7CFFFAFC5FE5F35CF666D" // MD5 of "secret123", upper hex

    @Test fun passwordHash() = assertEquals(md5u, TapoCrypto.md5u("secret123"))

    @Test fun digest() = assertEquals(
        "61889b597cfb752628c1a356f31cd9f1",
        TapoCrypto.digestResponse("admin", "TP-LINK IP-CAM", md5u, "abc123", "cn0001", "auth"),
    )

    @Test fun streamKey() = assertEquals("002c57659e404d2da48de556375feeb4", TapoCrypto.hex(TapoCrypto.streamKey("n0nce", md5u)))

    @Test fun decryptVideoPart() {
        val ct = "cc4a4ec01bf2470e1c3d3f8bcca9d7777d3f6e3ec35fa2ed880eba61ed7642dc".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val plain = TapoCrypto.decryptPart(TapoCrypto.streamKey("n0nce", md5u), TapoCrypto.streamIv("admin", "n0nce"), ct)
        assertEquals("Ghello-ts-packet", String(plain))
    }
}
