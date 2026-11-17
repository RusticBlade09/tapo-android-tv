package za.munnik.tapoatv

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Hashing/crypto for TP-Link's local camera protocol (as go2rtc tapo:// and pytapo do it). */
object TapoCrypto {
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun md5(s: String): ByteArray = MessageDigest.getInstance("MD5").digest(s.toByteArray())
    fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    fun md5u(s: String) = hex(md5(s)).uppercase()
    fun sha256u(s: String) = hex(sha256(s)).uppercase()

    /** HTTP Digest piece: md5 hex of parts joined with ':'. */
    fun hexMd5(vararg parts: String) = hex(md5(parts.joinToString(":")))

    fun digestResponse(user: String, realm: String, pw: String, nonce: String, cnonce: String, qop: String): String {
        val ha1 = hexMd5(user, realm, pw)
        val ha2 = hexMd5("POST", "/stream")
        return if (qop.isEmpty()) hexMd5(ha1, nonce, ha2) else hexMd5(ha1, nonce, "00000001", cnonce, qop, ha2)
    }

    fun streamKey(nonce: String, pw: String) = md5("$nonce:$pw")
    fun streamIv(user: String, nonce: String) = md5("$user:$nonce")

    /** Each video part is AES-128-CBC on its own (IV reset), PKCS#7 padded. */
    fun decryptPart(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val plain = c.doFinal(data)
        val pad = plain.last().toInt() and 0xFF
        return if (pad in 1..16) plain.copyOf(plain.size - pad) else plain
    }
}
