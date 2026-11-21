package za.munnik.tapoatv

import java.io.InputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * Tapo puts its sound in the MPEG-TS as stream type 0x90 (G.711 A-law, 8 kHz mono). FFmpeg doesn't know that type,
 * so we pick it out ourselves: PAT → PMT → the 0x90 PID → PES payload bytes → [onAudio]. Video is left alone.
 */
class TsAudio(private val onAudio: (ByteArray, Int, Int) -> Unit) {
    private val pkt = ByteArray(188)
    private var fill = 0
    private var pmtPid = -1
    private var audioPid = -1

    fun feed(b: ByteArray, off: Int, len: Int) {
        var i = off
        while (i < off + len) {
            if (fill == 0 && b[i] != 0x47.toByte()) { i++; continue } // resync on the 0x47 sync byte
            val n = minOf(188 - fill, off + len - i)
            System.arraycopy(b, i, pkt, fill, n)
            fill += n; i += n
            if (fill == 188) { packet(); fill = 0 }
        }
    }

    private fun u(i: Int) = pkt[i].toInt() and 0xFF

    private fun packet() {
        val pid = ((u(1) and 0x1F) shl 8) or u(2)
        val start = u(1) and 0x40 != 0
        val afc = (u(3) shr 4) and 3
        var p = if (afc and 2 != 0) 5 + u(4) else 4
        if (afc and 1 == 0 || p >= 188) return
        when {
            pid == 0 && start -> { // PAT: first real program → its PMT PID
                p += 1 + u(p)
                val end = p + 3 + (((u(p + 1) and 0x0F) shl 8) or u(p + 2)) - 4
                var e = p + 8
                while (e + 4 <= minOf(end, 188)) {
                    if (((u(e) shl 8) or u(e + 1)) != 0) { pmtPid = ((u(e + 2) and 0x1F) shl 8) or u(e + 3); return }
                    e += 4
                }
            }
            pid == pmtPid && start -> { // PMT: find the stream with type 0x90
                p += 1 + u(p)
                val end = minOf(p + 3 + (((u(p + 1) and 0x0F) shl 8) or u(p + 2)) - 4, 188)
                var e = p + 12 + (((u(p + 10) and 0x0F) shl 8) or u(p + 11))
                while (e + 5 <= end) {
                    if (u(e) == 0x90) { audioPid = ((u(e + 1) and 0x1F) shl 8) or u(e + 2); return }
                    e += 5 + (((u(e + 3) and 0x0F) shl 8) or u(e + 4))
                }
            }
            pid == audioPid -> {
                if (start) p += 9 + u(p + 8) // skip the PES header
                if (p < 188) onAudio(pkt, p, 188 - p)
            }
        }
    }

    companion object {
        /** G.711 A-law byte → 16-bit PCM sample. */
        fun alaw(byte: Int): Int {
            val a = byte xor 0x55
            val seg = (a shr 4) and 7
            var t = (a and 0x0F) shl 4
            t = if (seg == 0) t + 8 else (t + 0x108) shl (seg - 1)
            return if (a and 0x80 != 0) t else -t
        }
    }
}

/** Plays A-law through Windows' own sound (Java Sound). Never blocks the video: what doesn't fit is dropped. */
class AlawSpeaker : AutoCloseable {
    private val format = AudioFormat(8000f, 16, 1, true, false)
    private val line = AudioSystem.getSourceDataLine(format).apply { open(format, 6400); start() } // 400 ms buffer

    fun play(b: ByteArray, off: Int, len: Int) {
        val n = minOf(len, line.available() / 2) // shortcut: drops sound when the buffer is full, fine for live
        if (n <= 0) return
        val pcm = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = TsAudio.alaw(b[off + i].toInt() and 0xFF)
            pcm[2 * i] = v.toByte(); pcm[2 * i + 1] = (v shr 8).toByte()
        }
        line.write(pcm, 0, pcm.size)
    }

    override fun close() { line.stop(); line.close() }
}

/** Passes the TS through unchanged (to FFmpeg) while [TsAudio] taps the sound out of it. */
class AudioTap(private val src: InputStream, private val ts: TsAudio?) : InputStream() {
    override fun read(): Int = throw UnsupportedOperationException()
    override fun read(b: ByteArray, off: Int, len: Int): Int = src.read(b, off, len).also { if (it > 0) ts?.feed(b, off, it) }
    override fun close() = src.close()
}
