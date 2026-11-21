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
import androidx.compose.runtime.key
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
import androidx.compose.ui.window.WindowPosition
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
    // Non-null = our own full screen: a window WITHOUT border covering that monitor. Not Compose's Fullscreen: on Windows that
    // is Java's exclusive full screen, which minimizes itself as soon as another window gets the focus.
    val fullScreen = mutableStateOf<java.awt.Rectangle?>(null)
    var awtWindow: androidx.compose.ui.awt.ComposeWindow? = null

    Thread { findCameras(prefs, creds, cams, status) }.start()

    application {
        // Opens as a (maximized) window; F11 = full screen like the TV, no title bar. F11 again, or Esc in the grid = window.
        // A border can't be added/removed on a shown window → key() builds a new window for each mode (the cameras keep playing).
        val ws = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1280.dp, 760.dp))
        val fs = fullScreen.value
        key(fs) { Window(
            onCloseRequest = { cams.forEach { it.release() }; exitApplication() },
            state = if (fs == null) ws else rememberWindowState(position = WindowPosition(fs.x.dp, fs.y.dp), size = DpSize(fs.width.dp, fs.height.dp)),
            undecorated = fs != null,
            title = "TapoATV",
            onKeyEvent = { e ->
                val f = full.value
                if (e.type != KeyEventType.KeyDown) false
                else if (e.key == Key.F11 || (e.key == Key.Escape && f == null)) {
                    // the monitor the window is on now (AWT units = Compose dp on desktop)
                    fullScreen.value = if (e.key == Key.F11 && fs == null) awtWindow?.graphicsConfiguration?.bounds else null
                    true
                } else if (f == null) false
                else when (e.key) {
                    Key.Escape, Key.Backspace -> { full.value = null; f.sound = false; true }
                    // shortcut: step 10 = the TV's guess, never calibrated on the C530WS
                    Key.DirectionLeft -> f.move(-10, 0)
                    Key.DirectionRight -> f.move(10, 0)
                    Key.DirectionUp -> f.move(0, 10)
                    Key.DirectionDown -> f.move(0, -10)
                    else -> false
                }
            },
        ) {
            awtWindow = window
            Box(Modifier.fillMaxSize().background(BG)) {
                val f = full.value
                if (f != null) Tile(f, Modifier.fillMaxSize(), ContentScale.Fit) { full.value = null; f.sound = false }
                // The grid fills the whole screen, 2dp equal gaps. Pictures fill their tiles (Crop): on a 16:9 screen that cuts
                // nothing; on a wider screen a strip top + bottom. Full screen (one camera) always shows the whole picture.
                else Column(Modifier.fillMaxSize().padding(1.dp)) {
                    for (row in 0..1) Row(Modifier.weight(1f)) {
                        for (col in 0..1) {
                            val c = cams.getOrNull(row * 2 + col)
                            val m = Modifier.weight(1f).fillMaxHeight().padding(1.dp)
                            if (c == null) Box(m) else Tile(c, m, ContentScale.Crop) { full.value = c; c.sound = true }
                        }
                    }
                }
                status.value?.let { s -> // the one status card, bottom-right (TV look)
                    BasicText(s, Modifier.align(Alignment.BottomEnd).padding(24.dp)
                        .background(CARD, RoundedCornerShape(16.dp)).padding(horizontal = 24.dp, vertical = 16.dp),
                        style = TextStyle(color = Color.White, fontSize = 22.sp))
                }
            }
        } } // Window, key
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

/**
 * Like the TV: cameras seen before start playing AT ONCE (name remembered); the scan runs meanwhile and only adds new ones.
 * Up to 4, hub left out.
 */
private fun findCameras(prefs: Preferences, creds: Creds, cams: MutableList<Cam>, status: MutableState<String?>) {
    val known = prefs.get("cams", "").split(',').filter { it.isNotBlank() }
    SwingUtilities.invokeLater { add(cams, known.map { Cam(it, prefs.get("name_$it", it), creds) }) }
    if (known.isNotEmpty()) SwingUtilities.invokeLater { status.value = null }
    // First scan on Wi-Fi can find nothing (cold ARP cache vs the 400 ms connect) → once more.
    val ips = (known + Scanner.scan().ifEmpty { Scanner.scan() }).distinct().sortedBy { it.substringAfterLast('.').toInt() }
    log("scan done: ${ips.size}")
    val found = ips.parallelStream().map { ip -> // all logins at once, not one after the other
        TapoControl(ip, creds).info()?.takeUnless { it.optString("device_model").startsWith("H") } // H = HomeBase
            ?.let { ip to it.optString("device_alias", ip) }
    }.toList().filterNotNull().take(4) // shortcut: one page of 4, the TV's Dashboards for more cameras come later
    log("logins done: ${found.size}")
    prefs.put("cams", found.joinToString(",") { it.first })
    found.forEach { (ip, name) -> prefs.put("name_$ip", name) }
    SwingUtilities.invokeLater {
        add(cams, found.map { (ip, name) -> Cam(ip, name, creds) })
        status.value = if (cams.isEmpty()) "No camera found" else null
    }
}

/** Adds the cameras not shown yet (by IP), up to 4 tiles, and starts them in the grid. */
private fun add(cams: MutableList<Cam>, new: List<Cam>) {
    new.filter { n -> cams.none { it.ip == n.ip } }.take(4 - cams.size).forEach { cams += it; it.play() }
}

private val t0 = System.currentTimeMillis()
private fun log(s: String) = println("tapo +${System.currentTimeMillis() - t0} ms: $s")

/**
 * One camera: a decode thread (TapoStream → FFmpeg → Compose frames). On the PC always the HD stream, also in the grid
 * (boss 2026-11-21; the TV uses VGA there), so full screen is instant: it only switches [sound] on.
 */
private class Cam(val ip: String, val name: String, private val creds: Creds) {
    val frame = mutableStateOf<ImageBitmap?>(null)
    val error = mutableStateOf<String?>(null)
    private val control = TapoControl(ip, creds)
    @Volatile private var gen = 0

    @Volatile var sound = false // full screen = this camera's sound; the grid stays silent, like the TV

    fun play() {
        val my = ++gen
        Thread {
            while (my == gen) {
                try {
                    decode { my == gen }
                } catch (e: Exception) {
                    if (my != gen) break
                    log("$name: ${e.message} → retry in 30 s")
                    error.value = "no picture, retrying…"
                    // shortcut: retries every 30 s, also for a refused password; the TV waits 10 min then (camera lockout)
                    repeat(30) { if (my == gen) Thread.sleep(1000) }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun decode(alive: () -> Boolean) {
        val speaker = runCatching { AlawSpeaker() }.getOrNull() // no sound device = picture only
        try {
            TapoStream(ip, creds, hd = true).use { s ->
                s.connect()
                FFmpegFrameGrabber(AudioTap(s, speaker?.let { sp -> TsAudio { b, off, len -> if (sound) sp.play(b, off, len) } }), 0).apply {
                    format = "mpegts"
                    pixelFormat = avutil.AV_PIX_FMT_BGRA
                    // live: start fast. The unknown 0x90 sound track makes FFmpeg read up to probesize (5 MB = minutes at VGA).
                    // Don't let FFmpeg study the stream first: Tapo's unknown 0x90 sound track keeps it reading to probesize,
                    // and a quiet camera sends only ~13 kB/s (200 kB took 15 s). The decoder learns the size from the first frame.
                    setOption("analyzeduration", "0")
                    setOption("probesize", "32")
                    setOption("fflags", "nobuffer")
                    setVideoOption("thread_type", "slice") // frame threads hold back one frame per thread = live picture lags
                }.use { g ->
                    log("$name connected")
                    g.startUnsafe() // start() holds one lock for ALL grabbers → cameras would come up one after the other
                    log("$name probe done ${g.imageWidth}x${g.imageHeight} ${g.frameRate} fps")
                    var first = true
                    while (alive()) {
                        val f = g.grabImage() ?: throw java.io.IOException("stream ended")
                        val bytes = ByteArray(f.imageStride * f.imageHeight)
                        (f.image[0] as ByteBuffer).duplicate().apply { rewind() }.let { it.get(bytes, 0, minOf(bytes.size, it.remaining())) }
                        frame.value = Bitmap().apply {
                            installPixels(ImageInfo(f.imageWidth, f.imageHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), bytes, f.imageStride)
                        }.asComposeImageBitmap()
                        error.value = null
                        if (first) { first = false; log("$name first frame ${f.imageWidth}x${f.imageHeight}") }
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
