package za.munnik.tapoatv

import java.awt.Dimension
import java.io.InputStream
import java.util.prefs.Preferences
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPasswordField
import javax.swing.SwingUtilities
import uk.co.caprica.vlcj.media.callback.nonseekable.NonSeekableInputStreamMedia
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

/**
 * Windows version, step 2 = proof window: find the first camera on the network and play it live in HD
 * through the PC's VLC. Proves the shared :core code + VLC (picture AND Tapo's sound) before the real UI.
 */
fun main() {
    val prefs = Preferences.userRoot().node("za/munnik/tapoatv") // same idea as the TV: only the hashes are kept
    val creds = savedCreds(prefs) ?: askPassword(prefs) ?: return

    val vlc = EmbeddedMediaPlayerComponent()
    val frame = JFrame("TapoATV — searching for cameras…").apply {
        contentPane = vlc
        size = Dimension(1280, 760)
        setLocationRelativeTo(null)
        defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) = vlc.release()
        })
        isVisible = true
    }
    vlc.mediaPlayer().events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
        override fun playing(mp: MediaPlayer) = log("playing")
        override fun error(mp: MediaPlayer) = log("VLC error")
        override fun finished(mp: MediaPlayer) = log("stream ended")
        override fun videoOutput(mp: MediaPlayer, newCount: Int) {
            log("video ${mp.video().videoDimension()}")
            // Tapo sound is TS stream type 0x90 (A-law); this line tells whether VLC picked it up.
            Thread { Thread.sleep(2000); log("audio tracks: ${mp.audio().trackDescriptions().map { it.description() }}") }.start()
        }
    })

    Thread {
        // First scan on Wi-Fi can find nothing (cold ARP cache vs the 400 ms connect) → once more, and cameras seen before come first.
        val known = prefs.get("cams", "").split(',').filter { it.isNotBlank() }
        val ips = (known + Scanner.scan().ifEmpty { Scanner.scan() }).distinct()
        log("scan: ${ips.size} Tapo device(s)")
        val cam = ips.firstNotNullOfOrNull { ip ->
            TapoControl(ip, creds).info()?.takeUnless { it.optString("device_model").startsWith("H") } // H = HomeBase, no live picture
                ?.let { ip to it.optString("device_alias", ip) }
        }
        if (cam == null) {
            log("no camera answered (wrong password, or Third-Party Compatibility off)")
            SwingUtilities.invokeLater { frame.title = "TapoATV — no camera found" }
            return@Thread
        }
        log("camera ${cam.second}")
        prefs.put("cams", (listOf(cam.first) + known).distinct().joinToString(","))
        SwingUtilities.invokeLater {
            frame.title = "TapoATV — ${cam.second}"
            live = LiveMedia(cam.first, creds)
            vlc.mediaPlayer().media().play(live, ":demux=ts", ":live-caching=300")
        }
    }.start()
}

private var live: LiveMedia? = null // VLC calls into it natively: a hard reference stops the GC from freeing it mid-stream

/** VLC reads the decrypted MPEG-TS straight from TapoStream. */
private class LiveMedia(private val ip: String, private val creds: Creds) : NonSeekableInputStreamMedia() {
    override fun onOpenStream(): InputStream = TapoStream(ip, creds, hd = true).also { it.connect() }
    override fun onCloseStream(inputStream: InputStream) = inputStream.close()
    override fun onGetSize() = 0L
}

private fun savedCreds(p: Preferences): Creds? {
    val md5 = p.get("md5u", null) ?: return null
    val sha = p.get("sha256u", null) ?: return null
    return Creds(md5, sha)
}

private fun askPassword(p: Preferences): Creds? {
    val field = JPasswordField(24)
    val ok = JOptionPane.showConfirmDialog(null, arrayOf("Tapo account password:", field), "TapoATV",
        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
    val pw = String(field.password)
    if (ok != JOptionPane.OK_OPTION || pw.isEmpty()) return null
    val c = Creds(TapoCrypto.md5u(pw), TapoCrypto.sha256u(pw))
    p.put("md5u", c.md5u)
    p.put("sha256u", c.sha256u)
    return c
}

private fun log(s: String) = println("tapo: $s")
