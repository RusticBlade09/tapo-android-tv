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
import androidx.compose.ui.graphics.asComposeImageBitmap
import java.nio.ByteBuffer
import java.util.prefs.Preferences
import javax.swing.JOptionPane
import javax.swing.JPasswordField
import javax.swing.SwingUtilities
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * Windows version, step 3: the 2x2 grid like the TV (VGA, silent), double-click = that camera full screen in HD,
 * Esc / Backspace / double-click = back, arrow keys = PTZ. FFmpeg (packed in the app) decodes; each frame is drawn by
 * Compose, so our own cards and frames can sit over the video. Full screen plays the camera's sound (grid silent, like the TV).
 */
private val BG = Color(0xFF111111)
private val CARD = Color(0xFF202124)

fun main() {
    val prefs = Preferences.userRoot().node("za/munnik/tapoatv") // same idea as the TV: only the hashes are kept
    val creds = savedCreds(prefs) ?: askPassword(prefs) ?: return
    avutil.av_log_set_level(avutil.AV_LOG_ERROR) // FFmpeg is chatty about the Tapo stream's unknown sound track
    val cams = mutableStateListOf<Cam>()
    val full = mutableStateOf<Cam?>(null)
    val status = mutableStateOf<String?>("Searching for cameras…")

    Thread { findCameras(prefs, creds, cams, status) }.start()

    application {
        // Opens as a (maximized) window; F11 = truly full screen like the TV, no title bar. F11 again, or Esc in the grid = window.
        val ws = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1280.dp, 760.dp))
        Window(
            onCloseRequest = { cams.forEach { it.release() }; exitApplication() },
            state = ws,
            title = "TapoATV",
            onKeyEvent = { e ->
                val f = full.value
                if (e.type != KeyEventType.KeyDown) false
                else if (e.key == Key.F11 || (e.key == Key.Escape && f == null)) {
                    val toWindow = e.key == Key.Escape || ws.placement == WindowPlacement.Fullscreen
                    ws.placement = if (toWindow) WindowPlacement.Maximized else WindowPlacement.Fullscreen
                    true
                } else if (f == null) false
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
                if (f != null) Tile(f, Modifier.fillMaxSize(), ContentScale.Fit) { full.value = null; f.play(hd = false) }
                // The grid fills the whole screen, 2dp equal gaps. Pictures fill their tiles (Crop): on a 16:9 screen that cuts
                // nothing; on a wider screen a strip top + bottom. Full screen (one camera) always shows the whole picture.
                else Column(Modifier.fillMaxSize().padding(1.dp)) {
                    for (row in 0..1) Row(Modifier.weight(1f)) {
                        for (col in 0..1) {
                            val c = cams.getOrNull(row * 2 + col)
                            val m = Modifier.weight(1f).fillMaxHeight().padding(1.dp)
                            if (c == null) Box(m) else Tile(c, m, ContentScale.Crop) { full.value = c; c.play(hd = true) }
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
private fun Tile(c: Cam, m: Modifier, scale: ContentScale, onDoubleClick: () -> Unit) {
    Box(m.background(Color.Black).pointerInput(c) { detectTapGestures(onDoubleTap = { onDoubleClick() }) }) {
        c.frame.value?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = scale) }
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

/** One camera: a decode thread per play() (TapoStream → FFmpeg → Compose frames); a newer play() ends the older one. */
private class Cam(val ip: String, val name: String, private val creds: Creds) {
    val frame = mutableStateOf<ImageBitmap?>(null)
    val error = mutableStateOf<String?>(null)
    private val control = TapoControl(ip, creds)
    @Volatile private var gen = 0

    fun play(hd: Boolean) {
        val my = ++gen
        Thread {
            while (my == gen) {
                try {
                    decode(hd) { my == gen }
                } catch (e: Exception) {
                    if (my != gen) break
                    error.value = "no picture, retrying…"
                    // shortcut: retries every 30 s, also for a refused password; the TV waits 10 min then (camera lockout)
                    repeat(30) { if (my == gen) Thread.sleep(1000) }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun decode(hd: Boolean, alive: () -> Boolean) {
        val speaker = if (hd) runCatching { AlawSpeaker() }.getOrNull() else null // no sound device = picture only
        try {
            TapoStream(ip, creds, hd).use { s ->
                s.connect()
                FFmpegFrameGrabber(AudioTap(s, speaker?.let { sp -> TsAudio(sp::play) }), 0).apply {
                    format = "mpegts"
                    pixelFormat = avutil.AV_PIX_FMT_BGRA
                    // live: start fast. The unknown 0x90 sound track makes FFmpeg read up to probesize (5 MB = minutes at VGA).
                    setOption("analyzeduration", "1000000")
                    setOption("probesize", "200000")
                    setOption("fflags", "nobuffer")
                }.use { g ->
                    g.start()
                    while (alive()) {
                        val f = g.grabImage() ?: throw java.io.IOException("stream ended")
                        val bytes = ByteArray(f.imageStride * f.imageHeight)
                        (f.image[0] as ByteBuffer).duplicate().apply { rewind() }.let { it.get(bytes, 0, minOf(bytes.size, it.remaining())) }
                        frame.value = Bitmap().apply {
                            installPixels(ImageInfo(f.imageWidth, f.imageHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), bytes, f.imageStride)
                        }.asComposeImageBitmap()
                        error.value = null
                    }
                }
            }
        } finally {
            speaker?.close()
        }
    }

    fun move(x: Int, y: Int): Boolean {
        Thread { runCatching { control.move(x, y) }.onFailure { e -> error.value = "PTZ: ${e.message}" } }.start()
        return true
    }

    fun release() { gen++ }
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
