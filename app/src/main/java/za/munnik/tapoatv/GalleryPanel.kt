package za.munnik.tapoatv

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.media.MediaMuxer
import java.io.IOException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.ui.PlayerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** A screenshot or video the dock saved on the box (Pictures/TapoATV, Movies/TapoATV). Name = "<camera>_<yyyy-MM-dd_HH-mm-ss>.<ext>". */
class Saved(val uri: Uri, val name: String, val video: Boolean, val added: Long, val size: Long) {
    val cam get() = SavedFiles.camOf(name)
    val time = SavedFiles.timeOf(name) ?: added // a saved HUB recording: when it was filmed, not when it was saved
}

/** Our saved files in MediaStore (works the same on a phone) + the auto-delete of the oldest (boss 2026-11-17). */
object SavedFiles {
    const val MAX_TOTAL = 500L shl 20 // our files together
    const val MIN_FREE = 300L shl 20  // what the box must keep free

    fun camOf(name: String) = name.substringBeforeLast('.').substringBeforeLast('_').substringBeforeLast('_')
    fun timeOf(name: String) = runCatching { stamp().parse(name.substringBeforeLast('.').takeLast(19))!!.time / 1000 }.getOrNull()
    fun stamp() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US) // the time in every file name

    /** A new file in Pictures/TapoATV or Movies/TapoATV (MediaStore: no permission needed on Android 10+), hidden until [done]. */
    fun create(cr: ContentResolver, name: String, mime: String, dir: String): Uri {
        if (Build.VERSION.SDK_INT < 29) throw IOException("needs Android 10")
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/TapoATV")
            put(MediaStore.MediaColumns.IS_PENDING, 1) // hidden from other apps until it's complete
        }
        val where = if (dir == Environment.DIRECTORY_PICTURES) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        return cr.insert(where, v) ?: throw IOException("the box refused the file")
    }

    fun done(cr: ContentResolver, uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    /** A downloaded hub recording (Tapo TS) → Movies/TapoATV/[name] as MP4, which phones and WhatsApp can play. */
    fun saveMp4(cr: ContentResolver, ts: File, name: String) {
        val uri = create(cr, name, "video/mp4", Environment.DIRECTORY_MOVIES)
        try {
            cr.openFileDescriptor(uri, "rw")!!.use { fd ->
                val ex = MediaExtractor()
                try { ex.setDataSource(ts.path); tsToMp4(ex, MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)) }
                finally { ex.release() }
            }
            done(cr, uri)
        } catch (e: Exception) { runCatching { cr.delete(uri, null, null) }; throw e } // no half file left behind
    }

    /** Every saved file, newest first. Files still being written (IS_PENDING) are left out by MediaStore. */
    fun list(cr: ContentResolver): List<Saved> {
        if (Build.VERSION.SDK_INT < 29) return emptyList() // RELATIVE_PATH is Android 10+; the dock can't save before that either
        fun q(where: Uri, dir: String, video: Boolean) = cr.query(where,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$dir/TapoATV%"), null)?.use { c ->
            generateSequence { if (c.moveToNext()) Saved(ContentUris.withAppendedId(where, c.getLong(0)), c.getString(1), video, c.getLong(2), c.getLong(3)) else null }.toList()
        }.orEmpty()
        return (q(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES, false) +
            q(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES, true)).sortedByDescending { it.added }
    }

    fun free() = StatFs(Environment.getExternalStorageDirectory().path).availableBytes

    /** How many of the oldest to delete so ours stay under [maxTotal] and the box keeps [minFree]. The newest is always kept. */
    fun howMany(sizesOldestFirst: List<Long>, free: Long, maxTotal: Long = MAX_TOTAL, minFree: Long = MIN_FREE): Int {
        var total = sizesOldestFirst.sum()
        var f = free
        var n = 0
        while (n < sizesOldestFirst.size - 1 && (total > maxTotal || f < minFree)) { total -= sizesOldestFirst[n]; f += sizesOldestFirst[n]; n++ }
        return n
    }

    /** Deletes the oldest files as [howMany] says (at app start + after each save). Returns how many went. */
    fun trim(cr: ContentResolver): Int {
        val old = list(cr).reversed()
        return old.take(howMany(old.map { it.size }, free())).count { runCatching { cr.delete(it.uri, null, null) > 0 }.getOrDefault(false) }
    }
}

/**
 * Right-hand panel with the saved screenshots + videos (dock "Saved"), same look as the recordings panel.
 * OK = show it full screen (a video plays with the recording bar: pause / slow-mo), hold OK = delete (Cancel first).
 */
@OptIn(UnstableApi::class)
class GalleryPanel(
    private val act: Activity,
    private val status: (String?, Boolean) -> Unit, // the one bottom-right spinner card
    private val note: (String) -> Unit,
    private val live: (Boolean) -> Unit, // false = stop the live camera while a saved file fills the screen, true = start it again
) {
    private val panel = act.findViewById<View>(R.id.galPanel)
    private val title = act.findViewById<TextView>(R.id.galTitle)
    private val hintLine = act.findViewById<TextView>(R.id.galHints)
    private val list = act.findViewById<ListView>(R.id.galList)
    private val viewer = act.findViewById<View>(R.id.viewer)
    private val image = act.findViewById<ImageView>(R.id.viewImage)
    private val video = act.findViewById<PlayerView>(R.id.viewVideo)
    private val bar = act.findViewById<View>(R.id.clipBar) // the recording bar's look, shared
    private val barTitle = act.findViewById<TextView>(R.id.clipTitle)
    private val barHints = act.findViewById<TextView>(R.id.clipHints)
    private val barLine = act.findViewById<View>(R.id.clipLine)
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()     // list + full photos
    private val thumbBg = Executors.newSingleThreadExecutor() // thumbnails, one at a time (weak box)
    private val thumbs = HashMap<Uri, Bitmap?>()
    private val lengths = HashMap<Uri, Long>() // video length in ms, learnt with its thumbnail
    private val pending = HashSet<Uri>()
    private val width get() = 500 * act.resources.displayMetrics.density
    private val day = SimpleDateFormat("EEE d MMM", Locale.US)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var items = listOf<Saved>()
    private var sel = 0
    private var confirm = false // the list shows "Cancel / Delete it" for items[sel]
    private var held = false
    private var gen = 0
    private var shown: Saved? = null // full screen in the viewer
    private var player: ExoPlayer? = null
    private var loading = false // "Loading video…" card up
    private var loadAt = 0L

    var isOpen = false
        private set

    init {
        list.setOnItemClickListener { _, _, i, _ -> if (!confirm) view(i) else if (i == 1) delete(items[sel]) else showList(sel) }
    }

    fun open() {
        isOpen = true
        val g = ++gen
        status("Loading saved files…", true)
        val at = SystemClock.uptimeMillis()
        bg.execute {
            val r = runCatching { SavedFiles.list(act.contentResolver) }
            main.postAtTime({ // the spinner stays at least 2.5 s (boss), then the panel slides in
                if (g != gen) return@postAtTime
                r.onSuccess { status(null, false); items = it; slideIn(); showList(0) }
                    .onFailure { isOpen = false; note("⚠  Saved files: ${it.message}") }
            }, at + 2_500)
        }
    }

    private fun slideIn() {
        panel.translationX = width
        panel.visibility = View.VISIBLE
        panel.animate().translationX(0f).setDuration(200).withEndAction(null).start()
    }

    private fun slideOut() = panel.animate().translationX(width).setDuration(200).withEndAction { panel.visibility = View.GONE }.start()

    fun close() {
        if (!isOpen) return
        gen++
        isOpen = false
        if (shown != null) stopViewer(restartLive = true)
        if (panel.visibility != View.VISIBLE) { status(null, false); return } // still loading
        slideOut()
    }

    /** Gone at once, live NOT restarted (back to the grid / app hidden). */
    fun hideNow() {
        if (!isOpen) return
        if (panel.visibility != View.VISIBLE && shown == null) status(null, false)
        gen++; isOpen = false
        if (shown != null) stopViewer(restartLive = false)
        panel.visibility = View.GONE
    }

    /** Every key while the panel or the viewer is open comes here first. True = handled. */
    fun key(e: KeyEvent): Boolean {
        if (shown != null) return viewKey(e)
        val ok = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER
        if (ok && held) { if (e.action == KeyEvent.ACTION_UP) held = false; return true } // swallow the rest of a hold
        if (ok && e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0 && !confirm && items.isNotEmpty() && list.hasFocus()) {
            held = true
            list.isPressed = false // the press became a hold: no click on release
            askDelete(list.selectedItemPosition)
            return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_BACK) { if (e.action == KeyEvent.ACTION_DOWN) { if (confirm) showList(sel) else close() }; return true }
        return false
    }

    private fun showList(select: Int) {
        confirm = false
        title.text = if (items.isEmpty()) "Saved" else "Saved  ·  ${items.size}  ·  ${mb(items.sumOf { it.size })}"
        hintLine.text = if (items.isEmpty()) "Nothing saved yet. Hold OK on a camera → Screenshot or Record."
        else hints("OK" to "show", "hold OK" to "delete", "BACK" to "close")
        list.adapter = cards
        list.requestFocus()
        list.setSelection(select.coerceIn(0, maxOf(0, items.size - 1)))
    }

    private fun askDelete(i: Int) {
        val s = items.getOrNull(i) ?: return
        sel = i
        confirm = true
        title.text = "Delete this ${if (s.video) "video" else "photo"}?"
        hintLine.text = hints("OK" to "do it", "BACK" to "back")
        // Cancel first: a stray OK must not delete
        list.adapter = ArrayAdapter(act, R.layout.pos_row, listOf("Cancel", "Delete it  (${s.cam}, ${day.format(Date(s.time * 1000))} ${clock.format(Date(s.time * 1000))})"))
        list.requestFocus()
        list.setSelection(0)
    }

    private fun delete(s: Saved) {
        val i = items.indexOf(s)
        val ok = runCatching { act.contentResolver.delete(s.uri, null, null) > 0 }.getOrDefault(false)
        if (ok) { items = items - s; thumbs.remove(s.uri) }
        note(if (ok) "✓  Deleted" else "⚠  Could not delete it")
        showList(i)
    }

    // ---------- full screen ----------
    private fun view(i: Int) {
        val s = items.getOrNull(i) ?: return
        sel = i
        shown = s
        slideOut()
        live(false) // the weak box: no live HD decoding behind a saved file
        viewer.visibility = View.VISIBLE
        image.setImageBitmap(null)
        image.visibility = if (s.video) View.GONE else View.VISIBLE
        video.visibility = if (s.video) View.VISIBLE else View.GONE
        barLine.visibility = if (s.video) View.VISIBLE else View.GONE
        if (!s.video) {
            bg.execute {
                val bmp = runCatching { act.contentResolver.openInputStream(s.uri)!!.use { BitmapFactory.decodeStream(it) } }.getOrNull()
                main.post { if (shown === s) { image.setImageBitmap(bmp); if (bmp == null) note("⚠  Can't open this photo") } }
            }
            label(); poke()
            return
        }
        // A video: hidden + paused behind the spinner card until its first frame (and at least 2.5 s), like a recording.
        loading = true
        loadAt = SystemClock.uptimeMillis()
        status("Loading video…", true)
        video.alpha = 0f
        val p = ExoPlayer.Builder(act).build()
        // .ts = recorded from the live stream (Tapo sound reader), .mp4 = a saved hub recording
        val kind = if (s.name.endsWith(".mp4")) DefaultExtractorsFactory() else ExtractorsFactory { arrayOf(TapoTs.extractor()) }
        p.setMediaSource(ProgressiveMediaSource.Factory(DefaultDataSource.Factory(act), kind)
            .createMediaSource(MediaItem.fromUri(s.uri)))
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (player === p) label() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (player === p) label() }
            override fun onRenderedFirstFrame() { if (player === p) reveal(p) }
            override fun onPlayerError(error: PlaybackException) {
                if (player !== p) return
                reveal(p)
                barTitle.text = "⚠  Can't play this video: ${error.cause?.message ?: error.message}"
            }
        })
        p.playWhenReady = false
        p.prepare()
        video.player = p
        player = p
    }

    private fun reveal(p: ExoPlayer) = main.postDelayed({
        if (player !== p || !loading) return@postDelayed
        loading = false
        status(null, false)
        video.alpha = 1f
        if (p.playerError == null) p.play()
        label(); poke()
    }, maxOf(0L, 2_500L - (SystemClock.uptimeMillis() - loadAt)))

    private fun stopViewer(restartLive: Boolean) {
        if (loading) { loading = false; status(null, false) }
        player?.release(); player = null
        video.player = null
        shown = null
        image.setImageBitmap(null)
        viewer.visibility = View.GONE
        main.removeCallbacks(hideBar); main.removeCallbacks(tick)
        bar.visibility = View.GONE
        barLine.visibility = View.VISIBLE // the recordings use it
        if (restartLive) live(true)
    }

    /** Back from full screen to the list, on the same card. */
    private fun backToList() {
        stopViewer(restartLive = true)
        slideIn()
        showList(sel)
    }

    private fun viewKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true // everything stays here: no PTZ under a saved file
        val p = player
        val hidden = bar.visibility != View.VISIBLE
        poke()
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { backToList(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> if (!hidden) { backToList(); return true } // first ▲ just brings the bar back
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (p != null && !loading) {
                if (p.playbackState == Player.STATE_ENDED) { p.seekTo(0); p.play() } else p.playWhenReady = !p.playWhenReady
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> p?.setPlaybackSpeed(if (p.playbackParameters.speed > 0.5f) 0.5f else 0.25f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> p?.setPlaybackSpeed(1f)
        }
        label()
        return true
    }

    // ---------- the bar (same look + 3 s hide as the recording bar) ----------
    private val hideBar = Runnable {
        if (player?.playerError != null) return@Runnable // keep the error on screen
        bar.animate().alpha(0f).setDuration(300).withEndAction { bar.visibility = View.GONE; bar.alpha = 1f }.start()
    }
    private val tick = object : Runnable { // twice a second while the bar is up
        override fun run() { if (shown == null || bar.visibility != View.VISIBLE) return; progress(); main.postDelayed(this, 500) }
    }

    private fun poke() {
        if (loading) return
        bar.animate().cancel()
        bar.alpha = 1f
        bar.visibility = View.VISIBLE
        main.removeCallbacks(tick); main.post(tick)
        main.removeCallbacks(hideBar); main.postDelayed(hideBar, 3_000)
    }

    private fun label() {
        val s = shown ?: return
        val p = player
        val what = "${s.cam}   ·   ${day.format(Date(s.time * 1000))}  ${clock.format(Date(s.time * 1000))}"
        if (p == null) {
            barTitle.text = what
            barHints.text = hints("BACK" to "saved files")
            return
        }
        if (p.playerError != null) return // the error stays
        val state = when {
            p.playbackState == Player.STATE_ENDED -> "■  Ended"
            !p.playWhenReady -> "⏸  Paused"
            else -> "▶  Playing"
        }
        val speed = when (p.playbackParameters.speed) { 0.5f -> "   ·   ½× slow-mo"; 0.25f -> "   ·   ¼× slow-mo"; else -> "" }
        barTitle.text = "$state   ·   $what$speed"
        barHints.text = hints("OK" to if (p.playbackState == Player.STATE_ENDED) "replay" else "play / pause",
            "◀" to "slower", "▶" to "normal speed", "BACK" to "saved files")
        progress()
    }

    private fun progress() {
        val p = player ?: return
        val len = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: shown?.let { lengths[it.uri] } ?: 0L
        val pos = p.currentPosition.coerceAtLeast(0)
        act.findViewById<ProgressBar>(R.id.clipProgress).progress = if (len > 0) (pos.coerceAtMost(len) * 1000 / len).toInt() else 0
        act.findViewById<TextView>(R.id.clipTime).text = if (len > 0) "${time(pos)} / ${time(len)}" else time(pos)
    }

    // ---------- cards ----------
    private fun time(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
    private fun mb(b: Long) = if (b < (10L shl 20)) "%.1f MB".format(b / 1048576.0) else "${b shr 20} MB"

    private val cards = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: act.layoutInflater.inflate(R.layout.clip_card, parent, false)
            val s = items[i]
            v.findViewById<TextView>(R.id.time).text = clock.format(Date(s.time * 1000))
            v.findViewById<TextView>(R.id.info).text = "${s.cam}\n${day.format(Date(s.time * 1000))}\n" + // one per line: the card is narrow
                listOfNotNull(lengths[s.uri]?.let { time(it) }, mb(s.size)).joinToString("  ·  ")
            val row = v.findViewById<ViewGroup>(R.id.icons)
            row.removeAllViews()
            val px = (30 * act.resources.displayMetrics.density).toInt()
            row.addView(ImageView(act).apply { // photo or video: an icon instead of a word (boss)
                setImageResource(if (s.video) R.drawable.ic_dock_rec else R.drawable.ic_dock_shot)
                contentDescription = if (s.video) "Video" else "Photo"
                layoutParams = ViewGroup.MarginLayoutParams(px, px)
            })
            row.visibility = View.VISIBLE
            v.findViewById<View>(R.id.watched).visibility = View.GONE
            v.findViewById<ImageView>(R.id.thumb).setImageBitmap(thumbs[s.uri])
            if (!thumbs.containsKey(s.uri) && pending.add(s.uri)) loadThumb(s) // only rows on screen ask
            return v
        }
    }

    private fun loadThumb(s: Saved): Unit = thumbBg.execute {
        var len: Long? = null
        val bmp = runCatching {
            if (!s.video) act.contentResolver.openInputStream(s.uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 }) }
            else {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(act, s.uri, null)
                    len = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { it.containsKey(MediaFormat.KEY_DURATION) }
                        ?.getLong(MediaFormat.KEY_DURATION)?.div(1000)
                    tsFrame(ex, File(act.cacheDir, "saved.mp4"), 2_000_000) // the first 2 s are enough for a picture
                } finally { ex.release() }
            }
        }.onFailure { android.util.Log.w("thumb", "saved thumbnail failed", it) }.getOrNull()
        main.post {
            thumbs[s.uri] = bmp
            len?.let { lengths[s.uri] = it }
            pending.remove(s.uri)
            if (!confirm) cards.notifyDataSetChanged()
        }
    }
}
