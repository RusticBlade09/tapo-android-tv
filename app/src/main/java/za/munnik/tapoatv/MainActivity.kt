package za.munnik.tapoatv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.TextureView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.ui.PlayerView
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** 2x2 grid of cameras. OK = full screen (HD + PTZ on the arrows), Back = grid. MENU = new password / rescan. */
@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private class Cam(val ip: String, val tile: FrameLayout, val view: PlayerView, val label: TextView) {
        var player: ExoPlayer? = null
        var control: TapoControl? = null
        var name = ip
        var mac = ""
        var clip: Clip? = null // a hub recording is playing instead of live
        var live = false // first frame shown (or gave up) — the start-up spinner waits for every camera
    }

    private var scanning = false // network scan still running
    private var startup = false  // the "Searching…" card is up and waits for the scan + every first frame
    private var startupAt = 0L
    private val SEARCHING = "Searching for cameras on the network…"

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newCachedThreadPool()
    private val cams = mutableListOf<Cam>()
    private var full: Cam? = null
    private var creds: Creds? = null
    private var hub: Pair<String, TapoControl>? = null // the H500 HomeBase that keeps the recordings
    private val pos by lazy {
        PositionsPanel(this, { msg, busy -> status(msg, busy) }, { note(it) }) { msg ->
            if (findViewById<TextView>(R.id.status).text.toString() == msg) status(null)
        }
    }
    private val panel by lazy {
        RecordingsPanel(this, { creds }, { busy -> status(if (busy) "Loading recordings…" else null, busy) }, { clip ->
            full?.let { play(it, hd = true, clip) } ?: status(null) // Back was pressed while the clip was found
        }) { msg, finished -> if (finished) { note(msg); bg.execute { trim() } } else status(msg, busy = true) }
    }
    private val gal by lazy {
        GalleryPanel(this, { msg, busy -> status(msg, busy) }, { note(it) }) { on ->
            full?.let { c -> if (on) play(c, hd = true) else { c.player?.release(); c.player = null } }
        }
    }
    private val clipTime = SimpleDateFormat("EEE HH:mm:ss", Locale.US)
    private var clipLoadStart = 0L // when the "Loading recording…" spinner appeared
    private var okHeld = false // OK went down in full-screen live: release = recordings, hold = dock
    private var longOk = false // the dock opened on this press: swallow the rest of it

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        findViewById<View>(R.id.dockShot).setOnClickListener { hideDock(); full?.let { screenshot(it) } }
        findViewById<View>(R.id.dockRec).setOnClickListener { hideDock(); if (rec != null) stopRec() else full?.let { startRec(it) } }
        findViewById<View>(R.id.dockMic).setOnClickListener { hideDock(); full?.let { toggleMic(it) } }
        findViewById<View>(R.id.dockLink).setOnClickListener { hideDock(); full?.let { pickNeighbours(it) } }
        findViewById<View>(R.id.dockPos).setOnClickListener {
            hideDock()
            full?.let { c -> c.control?.let { pos.open(c.name, it) } ?: note("⚠  No connection to ${c.name}") }
        }
        findViewById<View>(R.id.dockSaved).setOnClickListener { hideDock(); gal.open() }
        bg.execute { trim() }
        findViewById<TextView>(R.id.dockHints).text = hints("◀ ▶" to "choose", "OK" to "do it", "BACK" to "close")
        val prefs = getSharedPreferences("tapo", MODE_PRIVATE)
        val md5 = prefs.getString("md5u", null)
        val sha = prefs.getString("sha256u", null)
        if (md5 == null || sha == null) askPassword() else { creds = Creds(md5, sha); findCameras() }
    }

    private fun askPassword() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this)
            .setTitle("Tapo password")
            .setMessage("Type your Tapo account password. Only a hash of it is stored, never the password itself.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                val pw = input.text.toString()
                val c = Creds(TapoCrypto.md5u(pw), TapoCrypto.sha256u(pw))
                getSharedPreferences("tapo", MODE_PRIVATE).edit().putString("md5u", c.md5u).putString("sha256u", c.sha256u).apply()
                creds = c
                findCameras()
            }
            .show()
    }

    private fun findCameras() {
        status(SEARCHING, busy = true)
        startupAt = SystemClock.uptimeMillis()
        releaseAll()
        val c = creds ?: return
        val prefs = getSharedPreferences("tapo", MODE_PRIVATE)
        // A busy camera can miss the scan, so cameras seen before are always kept — and started right away,
        // so their pictures come up while the scan runs. The one card spinner stays until all of it is done.
        // shortcut: an old IP stays forever if a camera moves; fine while the cameras have static IPs.
        val known = prefs.getStringSet("cams", emptySet())!!
        scanning = true
        startup = true
        showGrid(known.sortedBy { it.substringAfterLast('.').toInt() }.map { ip -> Triple(ip, TapoControl(ip, c), null) })
        bg.execute {
            val all = (Scanner.scan() + known).distinct().sortedBy { it.substringAfterLast('.').toInt() }
                .map { ip -> TapoControl(ip, c).let { ctl -> Triple(ip, ctl, bg.submit<org.json.JSONObject?> { ctl.info() }) } }
                .map { (ip, ctl, info) -> Triple(ip, ctl, info.get()) }
            // shortcut: a Tapo hub (H500 etc.) is spotted by its model name starting with "H"; it has no live video
            val isHub = { t: Triple<String, TapoControl, org.json.JSONObject?> -> t.third?.optString("device_model")?.startsWith("H") == true }
            val found = all.filterNot(isHub)
            val hubIp = all.firstOrNull(isHub)?.first ?: prefs.getString("hub", null)
            hub = hubIp?.let { ip -> ip to (all.firstOrNull { it.first == ip }?.second ?: TapoControl(ip, c)) }
            val edit = prefs.edit().putStringSet("cams", known + found.filter { it.third != null }.map { it.first }).putString("hub", hubIp)
            found.forEach { (ip, _, info) -> // remembered so the next start can label the tiles before the scan is done
                info?.optString("device_alias")?.takeIf { it.isNotEmpty() }?.let { edit.putString("name_$ip", it) }
                info?.optString("mac")?.takeIf { it.isNotEmpty() }?.let { edit.putString("mac_$ip", it) }
            }
            edit.apply()
            main.post {
                scanning = false
                if (found.map { it.first } == roster.map { it.first }) found.forEach { (ip, ctl, info) ->
                    // same cameras as already playing: keep the pictures, just take the fresh names/controls
                    roster = found
                    val cam = cams.firstOrNull { it.ip == ip } ?: return@forEach // on another dashboard page
                    cam.control = ctl
                    info?.optString("device_alias")?.takeIf { it.isNotEmpty() }?.let { cam.name = it; if (cam !== full) cam.label.text = it }
                    info?.optString("mac")?.takeIf { it.isNotEmpty() }?.let { cam.mac = it }
                } else { releaseAll(); showGrid(found) }
                liveCheck()
            }
        }
    }

    /** Hide the start-up spinner once the scan is done and every camera shows a picture (or gave up) — never before 2.5 s. */
    private fun liveCheck() {
        if (!startup || scanning || !cams.all { it.live }) return
        startup = false
        val empty = cams.isEmpty()
        main.postDelayed({
            // only replace our own card: meanwhile the boss may have opened recordings ("Loading recordings…")
            if (findViewById<TextView>(R.id.status).text.toString() == SEARCHING) status(if (empty) "No cameras found. Press MENU to search again." else null)
        }, maxOf(0L, 2_500L - (SystemClock.uptimeMillis() - startupAt)))
    }

    // Dashboards: every camera is in the roster; the grid shows one page of 4 (only that page streams — weak box).
    private var roster = listOf<Triple<String, TapoControl, org.json.JSONObject?>>()
    private var page = 0
    private fun pages() = maxOf(1, (roster.size + 3) / 4)

    private fun showGrid(found: List<Triple<String, TapoControl, org.json.JSONObject?>>) {
        roster = found
        page = page.coerceIn(0, pages() - 1)
        showPage()
    }

    /** Next / previous dashboard (wraps around). */
    private fun flip(by: Int) {
        cams.forEach { it.player?.release(); it.player = null }
        cams.clear()
        listOf(R.id.tile0, R.id.tile1, R.id.tile2, R.id.tile3).forEach {
            // clear the old page's last frame, which keep_content_on_player_reset would otherwise leave up
            val v = findViewById<FrameLayout>(it).getChildAt(0) as PlayerView
            v.player = null; v.setKeepContentOnPlayerReset(false); v.setKeepContentOnPlayerReset(true)
        }
        page = (page + by + pages()) % pages()
        showPage()
    }

    private fun pageLabel() {
        findViewById<TextView>(R.id.pagePill).apply {
            text = "◀    Dashboard ${page + 1} / ${pages()}    ▶"
            visibility = if (pages() > 1 && full == null) View.VISIBLE else View.GONE
        }
    }

    private fun showPage() {
        val tiles = listOf(R.id.tile0, R.id.tile1, R.id.tile2, R.id.tile3).map { findViewById<FrameLayout>(it) }
        val prefs = getSharedPreferences("tapo", MODE_PRIVATE)
        val shown = roster.drop(page * 4).take(4)
        shown.forEachIndexed { i, (ip, ctl, info) ->
            val t = tiles[i]
            val cam = Cam(ip, t, t.getChildAt(0) as PlayerView, t.getChildAt(1) as TextView)
            cam.control = ctl
            cam.name = info?.optString("device_alias")?.takeIf { it.isNotEmpty() } ?: prefs.getString("name_$ip", null) ?: ip
            cam.mac = info?.optString("mac")?.takeIf { it.isNotEmpty() } ?: prefs.getString("mac_$ip", "")!!
            cams += cam
            t.isFocusable = true
            t.setOnClickListener { if (full == null) openFull(cam) }
            play(cam, hd = false)
        }
        tiles.drop(shown.size).forEach { it.isFocusable = false; it.getChildAt(1).visibility = View.GONE }
        tiles[0].requestFocus()
        pageLabel()
    }

    private fun play(cam: Cam, hd: Boolean, clip: Clip? = null) {
        val c = creds ?: return
        val old = cam.player // released only after the new player owns the view, so the last frame stays up
        // a recording to the box only follows the HD live picture (a reconnect keeps it going)
        if (rec?.ip == cam.ip && (clip != null || !hd)) stopRec()
        if (clip != null) hideDock()
        cam.clip = clip
        cam.label.text = cam.name
        // no name labels (boss 2026-11-19); the label only comes up for an error
        cam.label.visibility = View.GONE
        // A recording: hidden + paused behind the spinner card until its first frame (and at least 2.5 s),
        // then picture, bar and playback start together (showClipBar).
        cam.view.alpha = if (clip != null) 0f else 1f
        if (hd) {
            findViewById<View>(R.id.clipBar).visibility = View.GONE
            if (clip != null) status("Loading recording…", busy = true) else status(null)
        }
        clipLoadStart = SystemClock.uptimeMillis()
        // Live: small buffers = close to live. Recording: room for the whole clip (up to 3 min), so a long pause
        // doesn't leave the hub waiting on an idle connection.
        val load = if (clip != null) DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 180_000, 1_000, 2_000).build()
        else DefaultLoadControl.Builder().setBufferDurationsMs(500, 2000, 250, 250).build()
        val p = ExoPlayer.Builder(this).setLoadControl(load).build()
        // Sound only in full screen (live + recordings); the grid stays silent and lighter on the CPU.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !hd).build()
        val source = ProgressiveMediaSource.Factory({ TapoDataSource(c) }, ExtractorsFactory { arrayOf(TapoTs.extractor()) })
            .createMediaSource(MediaItem.fromUri(
                if (clip != null) TapoDataSource.clipUri(hub!!.first, clip) else Uri.parse("tapo://${cam.ip}/${if (hd) "hd" else "vga"}")))
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (clip != null && cam.player === p) clipLabel(cam) }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (clip != null && cam.player === p) clipLabel(cam) }
            override fun onRenderedFirstFrame() {
                if (cam.player !== p) return
                if (clip != null) showClipBar(cam, p) else { cam.live = true; liveCheck() }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (clip != null) {
                    if (cam.player !== p) return
                    showClipBar(cam, p)
                    findViewById<TextView>(R.id.clipTitle).text = "⚠  Recording failed: ${error.cause?.message ?: error.message}"
                    return
                }
                cam.live = true // gave up for now: don't keep the start-up spinner waiting on this camera
                liveCheck()
                // A refused password counts toward the camera's lockout: wait long before trying again.
                val refused = error.cause?.message?.contains("password refused") == true
                cam.label.text = if (refused) "${cam.name} — password refused (is Third-Party Compatibility on?)"
                else "${cam.name} — no picture (asleep?), retrying…"
                cam.label.visibility = View.VISIBLE
                main.postDelayed({ if (cam.player === p) play(cam, cam === full) }, if (refused) 600_000L else 30_000L)
            }
        })
        p.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger("cam-${cam.ip}"))
        p.setMediaSource(source)
        p.playWhenReady = clip == null // a recording starts paused; showClipBar() presses play
        p.prepare()
        cam.view.player = p
        cam.player = p
        old?.release()
        if (clip != null) clipLabel(cam)
    }

    /** Reveal a loaded recording: after the spinner had at least 2.5 s, show picture + bar and start playing. */
    private fun showClipBar(cam: Cam, p: ExoPlayer) {
        val wait = maxOf(0L, 2_500L - (SystemClock.uptimeMillis() - clipLoadStart))
        main.postDelayed({
            if (cam.player !== p) return@postDelayed // Back was pressed meanwhile
            status(null)
            cam.view.alpha = 1f
            p.play()
            clipLabel(cam)
            pokeClipBar()
        }, wait)
    }

    /** Recording bar text while a recording plays: state, speed and the keys. */
    private fun clipLabel(cam: Cam) {
        val p = cam.player ?: return
        val clip = cam.clip ?: return
        val state = when {
            p.playbackState == Player.STATE_ENDED -> "■  Ended"
            !p.playWhenReady -> "⏸  Paused"
            else -> "▶  Playing"
        }
        val speed = when (p.playbackParameters.speed) { 0.5f -> "   ·   ½× slow-mo"; 0.25f -> "   ·   ¼× slow-mo"; else -> "" }
        findViewById<TextView>(R.id.clipTitle).text = "$state   ·   ${cam.name}   ·   ${clipTime.format(Date(clip.start * 1000))}$speed"
        findViewById<TextView>(R.id.clipHints).text = hints(
            "OK" to if (p.playbackState == Player.STATE_ENDED) "replay" else "play / pause",
            "◀" to "slower", "▶" to "normal speed", "▲" to "recordings", "BACK" to "live")
        clipProgress(cam)
    }

    /** The line under the recording bar's title: how far the clip has played, and "0:07 / 0:23". */
    private fun clipProgress(cam: Cam) {
        val p = cam.player ?: return
        val clip = cam.clip ?: return
        val len = maxOf(1L, (clip.end - clip.start) * 1000) // the hub's length: the player only learns it once fully downloaded
        val pos = p.currentPosition.coerceIn(0, len)
        findViewById<android.widget.ProgressBar>(R.id.clipProgress).progress = (pos * 1000 / len).toInt()
        findViewById<TextView>(R.id.clipTime).text = "%d:%02d / %d:%02d".format(pos / 60_000, pos / 1000 % 60, len / 60_000, len / 1000 % 60)
    }

    private val clipTick = object : Runnable { // twice a second while the recording bar is up; stops by itself
        override fun run() {
            val cam = full ?: return
            if (cam.clip == null || findViewById<View>(R.id.clipBar).visibility != View.VISIBLE) return
            clipProgress(cam)
            main.postDelayed(this, 500)
        }
    }

    // The recording bar hides 3 s after the last key (boss); any key shows it again — ▲ on a hidden bar only shows it.
    private val clipBar by lazy { findViewById<View>(R.id.clipBar) }
    private val hideClipBar = Runnable {
        if (full?.player?.playerError != null) return@Runnable // keep "Recording failed" on screen
        clipBar.animate().alpha(0f).setDuration(300).withEndAction { clipBar.visibility = View.GONE; clipBar.alpha = 1f }.start()
    }

    private fun pokeClipBar() {
        clipBar.animate().cancel()
        clipBar.alpha = 1f
        clipBar.visibility = View.VISIBLE
        main.removeCallbacks(clipTick)
        main.post(clipTick)
        main.removeCallbacks(hideClipBar)
        main.postDelayed(hideClipBar, 3_000)
    }

    /** Keys while a recording plays. True = handled. */
    private fun clipKey(cam: Cam, code: Int): Boolean {
        val p = cam.player ?: return false
        val hidden = clipBar.visibility != View.VISIBLE
        pokeClipBar()
        if (hidden && code == KeyEvent.KEYCODE_DPAD_UP) return true // first ▲ just brings the bar back
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                if (p.playbackState == Player.STATE_ENDED) { p.seekTo(0); p.play() } else p.playWhenReady = !p.playWhenReady
            KeyEvent.KEYCODE_DPAD_LEFT -> p.setPlaybackSpeed(if (p.playbackParameters.speed > 0.5f) 0.5f else 0.25f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> p.setPlaybackSpeed(1f)
            KeyEvent.KEYCODE_DPAD_UP -> panel.open(hub, cam.name, cam.mac)
            else -> return false
        }
        clipLabel(cam)
        return true
    }

    private fun openFull(cam: Cam) {
        full = cam
        cams.filter { it !== cam }.forEach { it.player?.release(); it.player = null }
        listOf(R.id.tile0, R.id.tile1, R.id.tile2, R.id.tile3).map { findViewById<View>(it) }
            .forEach { it.visibility = if (it === cam.tile) View.VISIBLE else View.GONE }
        findViewById<View>(if (cam.tile.parent === findViewById<View>(R.id.row0)) R.id.row1 else R.id.row0).visibility = View.GONE
        cam.tile.foreground = null // no blue focus frame in full screen
        pageLabel()
        play(cam, hd = true)
    }

    private fun closeFull() {
        val cam = full ?: return
        panel.hideNow()
        pos.hideNow()
        gal.hideNow()
        hideDock()
        stopRec()
        findViewById<View>(R.id.clipBar).visibility = View.GONE
        full = null
        pageLabel()
        cam.tile.foreground = getDrawable(R.drawable.focus)
        wakeFrame()
        listOf(R.id.row0, R.id.row1, R.id.tile0, R.id.tile1, R.id.tile2, R.id.tile3).forEach { findViewById<View>(it).visibility = View.VISIBLE }
        cams.forEach { play(it, hd = false) }
        cam.tile.requestFocus()
    }

    // The blue frame on the grid fades out after 5 s without a key press; any key brings it straight back.
    private var frameAnim: android.animation.ValueAnimator? = null
    private fun frame(alpha: Int) = listOf(R.id.tile0, R.id.tile1, R.id.tile2, R.id.tile3).forEach { id ->
        if (cams.any { it.tile.id == id && it.ip in marked }) return@forEach // a red event frame never fades
        findViewById<View>(id).apply { foreground?.mutate()?.alpha = alpha; invalidate() }
    }
    private val fadeFrame = Runnable {
        frameAnim = android.animation.ValueAnimator.ofInt(255, 0).setDuration(800).apply {
            addUpdateListener { frame(it.animatedValue as Int) }
            start()
        }
    }
    private fun wakeFrame() {
        frameAnim?.cancel()
        frame(255)
        main.removeCallbacks(fadeFrame)
        main.postDelayed(fadeFrame, 5_000)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) wakeFrame()
        if (e.action == KeyEvent.ACTION_DOWN && eventIp != null) endEventMode() // the boss took over: stay until Back
        val cam = full
        val ok = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER
        // OK in full-screen live: a short press opens the recordings, holding it opens the dock
        if (ok && (okHeld || longOk)) {
            if (e.action == KeyEvent.ACTION_UP) {
                if (okHeld && cam != null) firstTap { if (full === cam && cam.clip == null) panel.open(hub, cam.name, cam.mac) }
                okHeld = false; longOk = false
            } else if (okHeld && e.repeatCount > 0) { okHeld = false; longOk = true; if (cam != null) showDock() }
            return true
        }
        if (cam != null && pos.isOpen) { // before the DOWN-only filter: a hold needs the OK release too
            if (pos.key(e)) { if (!pos.isOpen) cam.tile.requestFocus(); return true }
            return super.dispatchKeyEvent(e) // the list and the name box handle the arrows and OK
        }
        if (cam != null && panel.isOpen && panel.key(e)) return true // hold OK = save needs the release too
        if (cam != null && gal.isOpen) { // same: hold OK = delete needs the release
            if (gal.key(e)) { if (!gal.isOpen) cam.tile.requestFocus(); return true }
            return super.dispatchKeyEvent(e)
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        if (e.keyCode == KeyEvent.KEYCODE_MENU) { askPassword(); return true }
        if (dock.visibility == View.VISIBLE) {
            if (e.keyCode == KeyEvent.KEYCODE_BACK) { hideDock(); return true }
            return super.dispatchKeyEvent(e) // Left/Right between the buttons, OK clicks
        }
        if (cam != null && panel.isOpen) {
            if (e.keyCode == KeyEvent.KEYCODE_BACK) { panel.close(); cam.tile.requestFocus(); return true }
            return super.dispatchKeyEvent(e) // the date and the list handle the arrows and OK
        }
        if (cam != null) when (e.keyCode) {
            // Back: recording -> live, live -> grid
            KeyEvent.KEYCODE_BACK -> { if (cam.clip != null) play(cam, hd = true) else closeFull(); return true }
            else -> if (cam.clip != null) { clipKey(cam, e.keyCode); return true } // no PTZ while a recording plays
        }
        if (cam != null && ok) {
            if (secondTap()) playNewest(cam) else okHeld = true // decided on release or on the first key repeat (above)
            return true
        }
        if (cam == null && ok) cams.firstOrNull { it.tile === currentFocus }?.let { c ->
            longOk = true // we decide here, not the tile's click: swallow the rest of this press
            if (secondTap()) { openFull(c); playNewest(c) } else firstTap { if (full == null && c in cams) openFull(c) }
            return true
        }
        if (cam == null && pages() > 1) {
            // grid: ▶ past the right column = next dashboard, ◀ past the left column = previous; CH+/CH- too
            val i = listOf(R.id.tile0, R.id.tile1, R.id.tile2, R.id.tile3).indexOf(currentFocus?.id)
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (i >= 0 && (i % 2 == 1 || i + 1 >= cams.size)) { flip(1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (i >= 0 && i % 2 == 0) { flip(-1); return true }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN -> { flip(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP -> { flip(-1); return true }
            }
        }
        if (cam != null && cam.clip == null) {
            // shortcut: step size 10 and up = +y are guesses, calibrate on the real C530WS
            val step = when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -10 to 0
                KeyEvent.KEYCODE_DPAD_RIGHT -> 10 to 0
                KeyEvent.KEYCODE_DPAD_UP -> 0 to 10
                KeyEvent.KEYCODE_DPAD_DOWN -> 0 to -10
                else -> null
            }
            if (step != null) {
                bg.execute {
                    val err = runCatching { cam.control?.move(step.first, step.second) }.exceptionOrNull()
                    if (err != null) main.post { cam.label.text = "${cam.name} — PTZ: ${err.message}"; cam.label.visibility = View.VISIBLE }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // Double OK (grid or full-screen live) = play that camera's newest recording. So a single OK waits 400 ms first.
    private var tapWait: Runnable? = null
    private fun firstTap(once: () -> Unit) { tapWait = Runnable { tapWait = null; once() }.also { main.postDelayed(it, 400) } }
    private fun secondTap(): Boolean {
        val t = tapWait ?: return false
        main.removeCallbacks(t); tapWait = null; longOk = true // swallow the rest of the second press
        return true
    }

    private fun playNewest(cam: Cam) {
        status("Loading recording…", busy = true)
        panel.playNewest(hub, cam.name, cam.mac) { msg -> note("⚠  $msg") }
    }

    private fun status(msg: String?, busy: Boolean = false, icon: Int = 0) {
        val box = findViewById<View>(R.id.statusBox)
        val off = 120 * resources.displayMetrics.density // slide distance, from the bottom-right corner
        if (msg == null) {
            if (box.visibility == View.VISIBLE) box.animate().translationX(off).translationY(off).alpha(0f).setDuration(250)
                .withEndAction { box.visibility = View.GONE }.start()
            return
        }
        findViewById<TextView>(R.id.status).apply {
            text = msg
            val px = (34 * resources.displayMetrics.density).toInt() // the 24dp icons, sized to the 26sp text
            setCompoundDrawablesRelative(if (icon != 0) getDrawable(icon)!!.apply { setBounds(0, 0, px, px) } else null, null, null, null)
            compoundDrawablePadding = px / 2
        }
        findViewById<View>(R.id.statusSpin).visibility = if (busy) View.VISIBLE else View.GONE
        if (box.visibility != View.VISIBLE) {
            box.translationX = off; box.translationY = off; box.alpha = 0f
            box.visibility = View.VISIBLE
        }
        box.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(350)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction(null).start()
    }

    /** A short message in the status card (no spinner) that goes away by itself after 2.5 s. */
    private fun note(msg: String, icon: Int = 0) {
        status(msg, icon = icon)
        main.postDelayed({ if (findViewById<TextView>(R.id.status).text.toString() == msg) status(null) }, 2_500)
    }

    // ---------- dock: screenshot + record (long-press OK in full-screen live) ----------
    private val dock by lazy { findViewById<View>(R.id.dock) }

    private fun showDock() {
        findViewById<TextView>(R.id.dockRec).text = if (rec == null) "Record" else "Stop"
        dock.visibility = View.VISIBLE
        findViewById<View>(R.id.dockShot).requestFocus()
        val cam = full ?: return
        micLabel(null) // asked fresh each time: the Tapo app may have changed it
        bg.execute {
            val m = runCatching { cam.control?.micMuted() }.getOrNull()
            main.post { if (full === cam) micLabel(m) }
        }
    }

    private var micMuted: Boolean? = null // the full-screen camera's microphone; null = not known (yet)

    private fun micLabel(muted: Boolean?) {
        micMuted = muted
        findViewById<TextView>(R.id.dockMic).apply {
            text = when (muted) { true -> "Mic off"; false -> "Mic on"; null -> "Mic …" }
            setCompoundDrawablesRelativeWithIntrinsicBounds(if (muted == true) R.drawable.ic_dock_mic_off else R.drawable.ic_dock_mic, 0, 0, 0)
        }
    }

    /** Turns the CAMERA's microphone off/on (camera setting: recordings on the hub go silent too). */
    private fun toggleMic(cam: Cam) {
        val muted = micMuted ?: run { note("⚠  Can't reach the camera's microphone"); return }
        bg.execute {
            val err = runCatching { (cam.control ?: throw IOException("no connection")).setMicMuted(!muted) }.exceptionOrNull()
            main.post {
                if (err == null) micMuted = !muted
                note(err?.let { "⚠  Microphone: ${it.message}" } ?: if (!muted) "Camera microphone OFF" else "Camera microphone ON")
            }
        }
    }

    // ---------- neighbour cameras (dock "Neighbours") ----------
    // prefs near_<ip> = the cameras next to it, kept both ways. An event on one shows the group side by side in the grid.
    private fun neighbours(ip: String): Set<String> = getSharedPreferences("tapo", MODE_PRIVATE).getStringSet("near_$ip", emptySet())!!

    private fun pickNeighbours(cam: Cam) {
        val prefs = getSharedPreferences("tapo", MODE_PRIVATE)
        val others = roster.map { it.first }.filter { it != cam.ip }
        if (others.isEmpty()) { note("⚠  No other cameras"); return }
        val near = neighbours(cam.ip)
        val checked = others.map { it in near }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Cameras next to ${cam.name}")
            .setMultiChoiceItems(others.map { prefs.getString("name_$it", null) ?: it }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("OK") { _, _ ->
                val edit = prefs.edit()
                val mine = HashSet<String>()
                others.forEachIndexed { i, ip ->
                    val theirs = HashSet(neighbours(ip)) // never change the set prefs handed out
                    if (checked[i]) { mine += ip; theirs += cam.ip } else theirs -= cam.ip
                    edit.putStringSet("near_$ip", theirs)
                }
                edit.putStringSet("near_${cam.ip}", mine).apply()
                note(if (mine.isEmpty()) "No neighbours" else "✓  Neighbours saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // cameras with a fresh event while neighbours are shown side by side: their tile frame turns red (boss 2026-11-19)
    private val marked = HashSet<String>()
    private fun mark(ip: String?) {
        if (ip == null) marked.clear() else marked += ip
        cams.forEach { // full screen: no frame, except red during an event (boss 2026-11-21)
            it.tile.foreground = if (it.ip in marked) getDrawable(R.drawable.event_frame) else if (it === full) null else getDrawable(R.drawable.focus)
        }
        if (ip == null) wakeFrame() // the blue frames are back: let them fade again
    }

    private fun hideDock() {
        if (dock.visibility != View.VISIBLE) return
        dock.visibility = View.GONE
        full?.tile?.requestFocus()
    }

    private val stamp = SavedFiles.stamp()

    /** A new file in Pictures/TapoATV or Movies/TapoATV on the box (MediaStore: no permission needed on Android 10). */
    private fun newFile(name: String, mime: String, dir: String): Pair<Uri, OutputStream> {
        val uri = SavedFiles.create(contentResolver, name, mime, dir)
        return uri to (contentResolver.openOutputStream(uri) ?: throw IOException("can't open the file"))
    }

    private fun fileDone(uri: Uri) = SavedFiles.done(contentResolver, uri)

    /** Saves the picture as it is now (full camera resolution) to Pictures/TapoATV. */
    private fun screenshot(cam: Cam) {
        val tv = cam.view.videoSurfaceView as? TextureView
        val size = cam.player?.videoSize
        val bmp = if (size != null && size.width > 0) tv?.getBitmap(size.width, size.height) else tv?.bitmap
        if (bmp == null) { note("⚠  No picture yet"); return }
        val name = "${cam.name}_${stamp.format(Date())}.jpg"
        bg.execute { // JPEG of a 1080p frame is slow on this box: off the main thread
            val r = runCatching {
                val (uri, out) = newFile(name, "image/jpeg", Environment.DIRECTORY_PICTURES)
                out.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                fileDone(uri)
            }
            main.post { note(r.exceptionOrNull()?.let { "⚠  Screenshot failed: ${it.message}" } ?: "✓  Screenshot saved") }
            trim()
        }
    }

    /** Auto-delete (boss 2026-11-17): oldest saved files go when ours pass 500 MB or the box has < 300 MB free. Run on bg. */
    private fun trim() {
        val n = runCatching { SavedFiles.trim(contentResolver) }.onFailure { android.util.Log.w("saved", "trim", it) }.getOrDefault(0)
        if (n > 0) main.postDelayed({ note("Box space: the $n oldest saved file${if (n > 1) "s were" else " was"} deleted") }, 2_600)
    }

    private class Rec(val ip: String, val uri: Uri, val out: OutputStream, val started: Long)
    private var rec: Rec? = null

    /** Copies the live HD stream as it arrives (TapoStream.tee) into Movies/TapoATV/<camera>_<time>.ts. */
    private fun startRec(cam: Cam) {
        val (uri, out) = runCatching { newFile("${cam.name}_${stamp.format(Date())}.ts", "video/mp2t", Environment.DIRECTORY_MOVIES) }
            .getOrElse { note("⚠  Can't record: ${it.message}"); return }
        val r = Rec(cam.ip, uri, out, SystemClock.uptimeMillis())
        rec = r
        TapoStream.tee[cam.ip] = out
        recTick(r)
    }

    private fun recTick(r: Rec) {
        if (rec !== r) return
        val s = (SystemClock.uptimeMillis() - r.started) / 1000
        // a full box would break the file: stop in time (the auto-delete runs after the save)
        if (s % 30 == 29L && runCatching { SavedFiles.free() }.getOrDefault(Long.MAX_VALUE) < (100L shl 20)) {
            stopRec(); main.postDelayed({ note("⚠  Box almost full — recording stopped") }, 2_600); return
        }
        findViewById<TextView>(R.id.recPill).apply { text = "●  REC   %d:%02d".format(s / 60, s % 60); visibility = View.VISIBLE }
        main.postDelayed({ recTick(r) }, 1_000)
    }

    private fun stopRec() {
        val r = rec ?: return
        rec = null
        TapoStream.tee.remove(r.ip)
        findViewById<View>(R.id.recPill).visibility = View.GONE
        bg.execute { runCatching { synchronized(r.out) { r.out.close() }; fileDone(r.uri) }; trim() }
        val s = (SystemClock.uptimeMillis() - r.started) / 1000
        note("✓  Video saved  (%d:%02d)".format(s / 60, s % 60))
    }

    // ---------- event → that camera full screen ----------
    // The hub's detection list (the same one behind the event icons on the recording cards) is asked every 5 s.
    // shortcut: polling, so an event shows a few seconds late; fine for a TV wall, push would need the cloud.
    private val lastEvent = HashMap<String, Long>() // hub device id -> newest event start already seen
    private var hubList: List<org.json.JSONObject>? = null
    private var polling = false
    private val eventPoll = object : Runnable {
        override fun run() { pollEvents(); main.postDelayed(this, 1_000) } // 1 s: the hub itself adds ~4 s
    }
    // a camera opened by an event goes back to the grid page we came from, 30 s after its last event — unless a key was pressed
    private var eventIp: String? = null
    private var eventFromPage = 0
    private val eventBack = Runnable {
        eventIp = null
        mark(null)
        if (full != null) closeFull()
        if (page != eventFromPage) flip(eventFromPage - page)
    }
    private fun endEventMode() { eventIp = null; main.removeCallbacks(eventBack); mark(null) }

    private fun pollEvents() {
        val h = hub ?: return
        if (polling) return
        polling = true
        bg.execute {
            val hits = runCatching {
                val now = System.currentTimeMillis() / 1000
                val list = hubList ?: h.second.hubCameras().also { hubList = it }
                list.mapNotNull { e ->
                    val dev = e.getString("device_id")
                    val last = h.second.detections(dev, e.optString("mac"), now - 120, now + 60).maxByOrNull { it.first }
                    val newest = last?.first ?: 0L
                    val before = lastEvent.put(dev, maxOf(newest, lastEvent[dev] ?: 0L))
                    if (newest > (before ?: 0L)) android.util.Log.d("events", "${e.optString("alias")} newest=$newest now=$now codes=${last?.second}") // delay check
                    // the first poll only learns what is already there; after that a newer start = a new event
                    if (before != null && newest > before) e.optString("mac") to last!!.second else null
                }
            }.onFailure { android.util.Log.w("events", "poll: ${it.message}") }.getOrDefault(emptyList())
            main.post { polling = false; hits.forEach { (mac, codes) -> eventFor(mac, codes) } }
        }
    }

    /** "Person + Motion": what set the event off, most specific first (higher hub codes are the specific ones). */
    private fun eventWhat(codes: List<Int>) =
        codes.distinct().sortedDescending().joinToString(" + ") { (EVENT_NAMES[it] ?: "event $it").replaceFirstChar { c -> c.uppercase() } }
            .ifEmpty { "Event" }

    /** Status card for an event: the icon of the most specific type that has one (else "●"), the words, the camera. */
    private fun eventNote(codes: List<Int>, name: String) {
        val icon = codes.sortedDescending().firstNotNullOfOrNull { EVENT_ICONS[it] } ?: 0
        note((if (icon == 0) "●  " else "") + "${eventWhat(codes)}  ·  $name", icon)
    }

    private fun eventFor(mac: String, codes: List<Int>) {
        val norm = { s: String -> s.uppercase().filter { it.isLetterOrDigit() } }
        val prefs = getSharedPreferences("tapo", MODE_PRIVATE)
        val i = roster.indexOfFirst { norm(prefs.getString("mac_${it.first}", "")!!) == norm(mac) }
        if (i < 0) return
        val f = full
        // never interrupt a recording playing, the recordings list, the dock or a video being saved
        if (f?.clip != null || panel.isOpen || pos.isOpen || gal.isOpen || dock.visibility == View.VISIBLE || rec != null) return
        val ip = roster[i].first
        val from = if (eventIp != null) eventFromPage else page
        // Neighbours on the same dashboard page (a car driving from one camera into the next): show them side by side
        // in the grid instead of jumping between them; red pill = which one had the event.
        // shortcut: neighbours on different dashboard pages fall back to "the newest event goes big".
        val group = neighbours(ip).filter { n -> roster.any { it.first == n } } + ip
        if (group.size > 1 && group.all { n -> roster.indexOfFirst { it.first == n } / 4 == i / 4 }) {
            if (f != null && eventIp == null && f.ip in group) return // the boss himself is already watching this spot
            if (f != null) closeFull()
            if (i / 4 != page) flip(i / 4 - page)
            mark(ip)
            eventNote(codes, cams.firstOrNull { it.ip == ip }?.name ?: ip)
            eventIp = ip; eventFromPage = from
            main.removeCallbacks(eventBack); main.postDelayed(eventBack, 30_000)
            return
        }
        if (f?.ip == ip) { // already big: an event-opened camera stays 30 s more; one the boss opened is left alone
            if (eventIp == f.ip) { main.removeCallbacks(eventBack); main.postDelayed(eventBack, 30_000) }
            return
        }
        if (f != null) closeFull()
        if (i / 4 != page) flip(i / 4 - page)
        val cam = cams.firstOrNull { it.ip == roster[i].first } ?: return
        openFull(cam)
        mark(cam.ip)
        eventNote(codes, cam.name)
        eventIp = cam.ip; eventFromPage = from
        main.removeCallbacks(eventBack); main.postDelayed(eventBack, 30_000)
    }

    private fun releaseAll() {
        if (full != null) closeFull()
        cams.forEach { it.player?.release(); it.player = null }
        cams.clear()
    }

    // Live video: drop the streams when the app is hidden, start fresh when it comes back.
    override fun onStop() {
        super.onStop()
        main.removeCallbacks(eventPoll)
        endEventMode()
        stopRec()
        gal.hideNow()
        cams.forEach { it.player?.release(); it.player = null }
    }

    override fun onStart() {
        super.onStart()
        main.removeCallbacks(eventPoll)
        main.postDelayed(eventPoll, 5_000)
        wakeFrame()
        val f = full
        if (f != null) play(f, hd = true) else cams.forEach { play(it, hd = false) }
    }

    override fun onDestroy() {
        releaseAll()
        bg.shutdownNow()
        super.onDestroy()
    }
}

// Shared by MainActivity's bars and the panels.
/** Key hints with each remote key drawn as a small grey key-cap: [OK] do it. */
fun hints(vararg h: Pair<String, String>) = android.text.SpannableStringBuilder().apply {
    h.forEachIndexed { i, (key, what) ->
        if (i > 0) append("      ")
        val at = length
        append(key)
        setSpan(KeyCap(), at, length, 0)
        append(" $what")
    }
}

private class KeyCap : android.text.style.ReplacementSpan() {
    override fun getSize(p: android.graphics.Paint, t: CharSequence, s: Int, e: Int, fm: android.graphics.Paint.FontMetricsInt?) =
        (p.measureText(t, s, e) + p.textSize * 0.9f).toInt() // cap padding + a little gap after it

    override fun draw(c: android.graphics.Canvas, t: CharSequence, s: Int, e: Int, x: Float, top: Int, y: Int, bottom: Int, p: android.graphics.Paint) {
        val pad = p.textSize * 0.35f
        val old = p.color
        p.color = 0xFF3C4043.toInt()
        c.drawRoundRect(x, top.toFloat(), x + p.measureText(t, s, e) + 2 * pad, bottom.toFloat(), pad, pad, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawText(t, s, e, x + pad, y.toFloat(), p)
        p.color = old
    }
}
