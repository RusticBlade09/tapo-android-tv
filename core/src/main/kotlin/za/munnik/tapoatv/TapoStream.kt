package za.munnik.tapoatv

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID

/** Saved password hashes — the raw Tapo password is never stored. */
data class Creds(val md5u: String, val sha256u: String)

/** One recording on the H500 hub (unix seconds). */
data class Clip(val devId: String, val mac: String, val start: Long, val end: Long)

/**
 * MPEG-TS from port 8800, decrypted. Read it like any InputStream.
 * Live: a camera, steps as go2rtc pkg/tapo (digest login -> Key-Exchange nonce -> preview -> AES parts).
 * clip != null: a recording from the H500 hub (type=download, as Sujeom/tapo-h500-local-recordings); ends at "finished".
 */
class TapoStream(
    private val host: String, private val creds: Creds, private val hd: Boolean, private val clip: Clip? = null,
) : InputStream() {
    companion object {
        val PLAYER: String = UUID.randomUUID().toString() // one player id per app run
        /** Camera ip -> file being recorded: the HD live TS is copied there as it arrives (MainActivity starts/stops it). */
        val tee = java.util.concurrent.ConcurrentHashMap<String, java.io.OutputStream>()

        /** True if this TS data holds an H.264 SPS (start code 00 00 01, NAL type 7) = a key frame begins here. */
        fun hasSps(b: ByteArray): Boolean {
            for (i in 0 until b.size - 3) if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1 && (b[i + 3].toInt() and 0x1F) == 7) return true
            return false
        }
    }
    private var session = ""
    private var ended = false
    private var sock = Socket()
    private lateinit var input: BufferedInputStream
    private lateinit var key: ByteArray
    private lateinit var iv: ByteArray
    private var buf = ByteArray(0)
    private var pos = 0
    private var teeTo: java.io.OutputStream? = null // the recording this stream is writing into

    fun connect() {
        val (second, pw) = login(forceMd5 = false)
            ?: run { reopen(); login(forceMd5 = true) } // some firmware asks SHA256 but only takes MD5 (pytapo)
            ?: throw IOException("Camera $host: password refused")

        val kx = second.headers["key-exchange"] ?: throw IOException("Camera $host: no Key-Exchange")
        val nonce = Regex("nonce=\"([^\"]*)\"") /* the hub may send an empty nonce */.find(kx)?.groupValues?.get(1) ?: throw IOException("no nonce")
        var user = "admin"
        var pass = pw
        if (kx.contains("username=\"none\"")) { user = "none"; pass = "TPL075526460603" } // old firmware (CVE-2022-37255)
        key = TapoCrypto.streamKey(nonce, pass)
        iv = TapoCrypto.streamIv(user, nonce)

        val res = if (hd) "HD" else "VGA"
        val body = if (clip == null) """{"params":{"preview":{"audio":["default"],"channels":[0],"resolutions":["$res"]},"method":"get"},"seq":1,"type":"request"}"""
        else """{"type":"request","seq":1,"params":{"method":"get","download":{"dev_id":"${clip.devId}","mac":"${clip.mac}","channels":[0],"client_id":1,"start_time":"${clip.start}","end_time":"${clip.end}","media_type":0,"player_id":"$PLAYER"}}}"""
        write("----client-stream-boundary--\r\nContent-Type: application/json\r\n" +
            (if (clip != null) "X-Data-Window-Size: 50\r\n" else "") + "Content-Length: ${body.length}\r\n\r\n$body\r\n")
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (pos >= buf.size) if (!nextVideoPart()) return -1
        val n = minOf(len, buf.size - pos)
        System.arraycopy(buf, pos, b, off, n)
        pos += n
        return n
    }

    override fun close() = sock.close()

    /** Loads the next video part into buf. False = a recording has finished. */
    private fun nextVideoPart(): Boolean {
        while (!ended) {
            val line = readLine() ?: if (clip != null) return false else throw IOException("Camera $host closed the stream")
            if (!line.startsWith("--")) continue // CRLF between parts
            val h = readHeaders()
            val data = readExact(h["content-length"]?.toIntOrNull() ?: 0)
            h["x-session-id"]?.let { session = it }
            if (h["content-type"] == "video/mp2t") {
                buf = if (h["x-if-encrypt"] == "0") data else TapoCrypto.decryptPart(key, iv, data)
                pos = 0
                // a failed or just-closed recording must never break the live picture
                // and starts on a key frame (the camera sends SPS before each one), so the file opens cleanly anywhere
                val o = if (clip == null && hd) tee[host] else null
                if (o == null) teeTo = null else if (teeTo !== o && hasSps(buf)) teeTo = o
                teeTo?.let { runCatching { synchronized(it) { it.write(buf) } } }
                val seq = h["x-data-sequence"]?.toIntOrNull()
                if (clip != null && seq != null && seq % 25 == 0) ack(seq) // the hub pauses without acks
                return true
            }
            if (clip != null && String(data).contains("\"finished\"")) ended = true
        }
        return false
    }

    private fun ack(seq: Int) {
        val body = """{"type":"notification","params":{"event_type":"stream_sequence"}}"""
        write("----client-stream-boundary--\r\nContent-Type: application/json\r\nX-Session-Id: $session\r\n" +
            "X-Data-Received: $seq\r\nContent-Length: ${body.length}\r\n\r\n$body\r\n")
    }

    /** Digest login. Returns the 200 reply + the hash used, or null on 401. */
    private fun login(forceMd5: Boolean): Pair<Reply, String>? {
        val first = request(null)
        if (!first.status.contains(" 401")) throw IOException("Camera $host: expected 401, got ${first.status}")
        val www = first.headers["www-authenticate"] ?: throw IOException("Camera $host: no digest challenge")
        val sha = www.contains("encrypt_type=\"3\"") && !forceMd5
        if (forceMd5 && !www.contains("encrypt_type=\"3\"")) return null // MD5 was already tried
        val pw = if (sha) creds.sha256u else creds.md5u
        skipBody(first)
        val auth = authHeader(www, "admin", pw)
        val second = try { request(auth) } catch (e: IOException) { reopen(); request(auth) }
        if (second.status.contains(" 401")) return null
        if (!second.status.contains(" 200")) throw IOException("Camera $host: ${second.status}")
        return second to pw
    }

    // ---------- tiny HTTP-over-socket helpers ----------
    private class Reply(val status: String, val headers: Map<String, String>)

    private fun reopen() {
        sock.close()
        sock = Socket()
    }

    private fun request(auth: String?): Reply {
        if (!sock.isConnected) {
            sock.connect(InetSocketAddress(host, 8800), 5000)
            sock.soTimeout = 10_000
            input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
        }
        val path = if (clip == null) "/stream" else "/stream?deviceId=${clip.devId}&type=download&playerId=$PLAYER&media_type=0"
        write("POST $path HTTP/1.1\r\nHost: $host:8800\r\n" + (if (clip != null) "X-Client-UUID: $PLAYER\r\n" else "") +
            "Content-Type: multipart/mixed; boundary=--client-stream-boundary--\r\n" +
            (if (auth != null) "Authorization: $auth\r\n" else "") + "\r\n")
        val status = readLine() ?: throw IOException("Camera $host: no reply")
        return Reply(status, readHeaders())
    }

    private fun skipBody(r: Reply) {
        readExact(r.headers["content-length"]?.toIntOrNull() ?: 0)
    }

    private fun authHeader(www: String, user: String, pw: String): String {
        val p = Regex("(\\w+)=\"?([^\",]*)\"?").findAll(www).associate { it.groupValues[1] to it.groupValues[2] }
        val realm = p["realm"] ?: ""
        val nonce = p["nonce"] ?: ""
        val qop = p["qop"] ?: ""
        val cnonce = TapoCrypto.hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val resp = TapoCrypto.digestResponse(user, realm, pw, nonce, cnonce, qop)
        var h = "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"/stream\", " +
            "qop=$qop, nc=00000001, cnonce=\"$cnonce\", response=\"$resp\""
        p["opaque"]?.let { h += ", opaque=\"$it\", algorithm=MD5" }
        return h
    }

    private fun write(s: String) {
        sock.getOutputStream().write(s.toByteArray())
        sock.getOutputStream().flush()
    }

    private fun readHeaders(): Map<String, String> {
        val h = HashMap<String, String>()
        while (true) {
            val line = readLine() ?: return h
            if (line.isEmpty()) return h
            val i = line.indexOf(':')
            if (i > 0) h[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
    }

    private fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun readExact(n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) throw IOException("Camera $host: stream cut")
            got += r
        }
        return out
    }
}
