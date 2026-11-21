package za.munnik.tapoatv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.prefs.Preferences
import javax.swing.JOptionPane
import javax.swing.JPasswordField
import javax.swing.SwingUtilities
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.media.callback.nonseekable.NonSeekableInputStreamMedia
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat

/**
 * Windows version, step 3: the 2x2 grid like the TV (VGA, silent), double-click = that camera full screen in HD,
 * Esc / Backspace / double-click = back, arrow keys = PTZ. VLC decodes; each frame is copied into Compose
 * (not a native window), so our own cards and frames can be drawn over the video later.
 */
private val BG = Color(0xFF111111)
private val CARD = Color(0xFF202124)
private val vlc by lazy { MediaPlayerFactory() }

fun main() {
    val prefs = Preferences.userRoot().node("za/munnik/tapoatv") // same idea as the TV: only the hashes are kept
    val creds = savedCreds(prefs) ?: askPassword(prefs) ?: return
    val cams = mutableStateListOf<Cam>()
    val full = mutableStateOf<Cam?>(null)
    val status = mutableStateOf<String?>("Searching for cameras…")

    Thread { findCameras(prefs, creds, cams, status) }.start()

    application {
        Window(
            onCloseRequest = { cams.forEach { it.release() }; exitApplication() },
            state = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1280.dp, 760.dp)),
            title = "TapoATV",
            onKeyEvent = { e ->
                val f = full.value
                if (e.type != KeyEventType.KeyDown || f == null) false
                else when (e.key) {
                    Key.Escape, Key.Backspace -> { full.value = null; f.play(hd = false); true }
                    // shortcut: step 10 = the TV's guess, never calibrated on the C530WS
                    Key.DirectionLeft -> f.move(-10, 0)
                    Key.DirectionRight -> f.move(10, 0)
                    Key.DirectionUp -> f.move(0, 10)
                    Key.DirectionDown -> f.move(0, -10)
                    else -> false
                }
            },
        ) {
            Box(Modifier.fillMaxSize().background(BG)) {
                val f = full.value
                if (f != null) Tile(f, Modifier.fillMaxSize()) { full.value = null; f.play(hd = false) }
                else Column(Modifier.fillMaxSize().padding(1.dp)) { // 2dp equal gaps everywhere, like the TV grid
                    for (row in 0..1) Row(Modifier.weight(1f)) {
                        for (col in 0..1) {
                            val c = cams.getOrNull(row * 2 + col)
                            val m = Modifier.weight(1f).fillMaxHeight().padding(1.dp)
                            if (c == null) Box(m) else Tile(c, m) { full.value = c; c.play(hd = true) }
                        }
                    }
                }
                status.value?.let { s -> // the one status card, bottom-right (TV look)
                    BasicText(s, Modifier.align(Alignment.BottomEnd).padding(24.dp)
                        .background(CARD, RoundedCornerShape(16.dp)).padding(horizontal = 24.dp, vertical = 16.dp),
                        style = TextStyle(color = Color.White, fontSize = 22.sp))
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Tile(c: Cam, m: Modifier, onDoubleClick: () -> Unit) {
    Box(m.background(Color.Black).pointerInput(c) { detectTapGestures(onDoubleTap = { onDoubleClick() }) }) {
        c.frame.value?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        c.error.value?.let { // like the TV: the name only comes up for an error
            BasicText("${c.name} — $it", Modifier.align(Alignment.TopEnd).padding(8.dp)
                .background(CARD, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                style = TextStyle(color = Color.White, fontSize = 18.sp))
        }
    }
}

/** Known cameras first (a busy one can miss the scan), then the scan; up to 4, hub left out. */
private fun findCameras(prefs: Preferences, creds: Creds, cams: MutableList<Cam>, status: MutableState<String?>) {
    val known = prefs.get("cams", "").split(',').filter { it.isNotBlank() }
    // First scan on Wi-Fi can find nothing (cold ARP cache vs the 400 ms connect) → once more.
    val ips = (known + Scanner.scan().ifEmpty { Scanner.scan() }).distinct().sortedBy { it.substringAfterLast('.').toInt() }
    val found = ips.mapNotNull { ip ->
        val info = TapoControl(ip, creds).info() ?: return@mapNotNull null
        if (info.optString("device_model").startsWith("H")) null else Cam(ip, info.optString("device_alias", ip), creds) // H = HomeBase
    }.take(4) // shortcut: one page of 4, the TV's Dashboards for more cameras come later
    prefs.put("cams", found.joinToString(",") { it.ip })
    SwingUtilities.invokeLater {
        cams += found
        found.forEach { it.play(hd = false) }
        status.value = if (found.isEmpty()) "No camera found" else null
    }
}

/** One camera: its own VLC player, frames copied into [frame] for Compose. */
private class Cam(val ip: String, val name: String, private val creds: Creds) {
    val frame = mutableStateOf<ImageBitmap?>(null)
    val error = mutableStateOf<String?>(null)
    private val control = TapoControl(ip, creds)
    private val player = vlc.mediaPlayers().newEmbeddedMediaPlayer()
    private var media: LiveMedia? = null // VLC calls into it natively: a hard reference stops the GC from freeing it mid-stream
    private var hd = false

    init {
        val frames = Frames(frame)
        player.videoSurface().set(vlc.videoSurfaces().newVideoSurface(frames, frames, true))
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) { error.value = null }
            override fun error(mp: MediaPlayer) = retry()
            override fun finished(mp: MediaPlayer) = retry()
        })
    }

    fun play(hd: Boolean) {
        this.hd = hd
        media = LiveMedia(ip, creds, hd)
        player.media().play(media, ":demux=ts", ":live-caching=300")
    }

    // shortcut: retries every 30 s, also for a refused password; the TV waits 10 min then (camera lockout) — copy that if needed
    private fun retry() {
        error.value = "no picture, retrying…"
        Thread { Thread.sleep(30_000); SwingUtilities.invokeLater { play(hd) } }.start()
    }

    fun move(x: Int, y: Int): Boolean {
        Thread { runCatching { control.move(x, y) }.onFailure { e -> error.value = "PTZ: ${e.message}" } }.start()
        return true
    }

    fun release() = player.release()
}

/** VLC hands us each decoded picture (RV32 = BGRA bytes) → one reused BufferedImage → Compose. */
private class Frames(private val out: MutableState<ImageBitmap?>) : BufferFormatCallback, RenderCallback {
    private var img: BufferedImage? = null

    override fun getBufferFormat(w: Int, h: Int): BufferFormat {
        img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        return RV32BufferFormat(w, h)
    }

    override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}

    override fun display(mp: MediaPlayer, buffers: Array<ByteBuffer>, format: BufferFormat) {
        val i = img ?: return
        buffers[0].order(ByteOrder.nativeOrder()).asIntBuffer().get((i.raster.dataBuffer as DataBufferInt).data)
        out.value = i.toComposeImageBitmap()
    }
}

/** VLC reads the decrypted MPEG-TS straight from TapoStream. */
private class LiveMedia(private val ip: String, private val creds: Creds, private val hd: Boolean) : NonSeekableInputStreamMedia() {
    override fun onOpenStream(): InputStream = TapoStream(ip, creds, hd).also { it.connect() }
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
