package za.munnik.tapoatv

import android.app.Activity
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/** Hub event codes → words (also used by the event status card in MainActivity). */
val EVENT_NAMES = mapOf(22 to "unknown face", 20 to "face", 19 to "camera tampering", 17 to "doorbell",
    10 to "missed doorbell", 9 to "pet", 8 to "vehicle", 6 to "person", 4 to "line crossing", 2 to "motion")

/** Our own event icons (cards in the panel + the event status card). */
val EVENT_ICONS = mapOf(6 to R.drawable.ic_ev_person, 9 to R.drawable.ic_ev_pet, 8 to R.drawable.ic_ev_vehicle,
    2 to R.drawable.ic_ev_motion, 4 to R.drawable.ic_ev_line, 19 to R.drawable.ic_ev_tamper)

/**
 * First picture of a Tapo TS as a 320x180 thumbnail ([ex] already has its data source; the caller releases it).
 * The box's frame grabber can't read Tapo TS, but it reads MP4: the video samples (only the first [maxUs]) are repacked first.
 */
fun tsFrame(ex: MediaExtractor, mp4: File, maxUs: Long = Long.MAX_VALUE): Bitmap? {
    mp4.delete()
    tsToMp4(ex, MediaMuxer(mp4.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), maxUs)
    val mmr = MediaMetadataRetriever()
    try { mmr.setDataSource(mp4.path); return mmr.getFrameAtTime(0)?.let { Bitmap.createScaledBitmap(it, 320, 180, true) } }
    finally { mmr.release() }
}

/** Repacks the VIDEO of a Tapo TS into [mux] (MP4). No sound: MP4 can't hold Tapo's G.711 (hub recordings are silent anyway). */
fun tsToMp4(ex: MediaExtractor, mux: MediaMuxer, maxUs: Long = Long.MAX_VALUE) {
    val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
    ex.selectTrack(track)
    val out = mux.addTrack(ex.getTrackFormat(track))
    mux.start()
    val buf = ByteBuffer.allocate(4 shl 20)
    val info = MediaCodec.BufferInfo()
    val t0 = ex.sampleTime
    while (true) {
        val n = ex.readSampleData(buf, 0)
        if (n < 0 || ex.sampleTime - t0 > maxUs) break
        val key = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
        info.set(0, n, ex.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        mux.writeSampleData(out, buf, info)
        ex.advance()
    }
    mux.stop()
    mux.release()
}

/**
 * The right-hand recordings panel in full screen: a date at the top (Left/Right = other day),
 * then one card per recording with a thumbnail, time, length and what triggered it.
 * Everything comes from the H500 HomeBase; [onPlay] gets the chosen clip.
 */
class RecordingsPanel(
    private val act: Activity,
    private val creds: () -> Creds?,
    private val onBusy: (Boolean) -> Unit, // "Loading recordings…" spinner card while the panel waits for its first list
    private val onPlay: (Clip) -> Unit,
    private val onSave: (msg: String, finished: Boolean) -> Unit, // "Save to this device": progress card, then the result
) {
    private class Item(val clip: Clip, val codes: List<Int>)

    private val panel = act.findViewById<View>(R.id.flyout)
    private val title = act.findViewById<TextView>(R.id.flyoutTitle)
    private val hintLine = act.findViewById<TextView>(R.id.flyoutHints)
    private val HINTS = hints("OK" to "play", "hold OK" to "save to this device")
    private val date = act.findViewById<TextView>(R.id.flyoutDate)
    private val sort = act.findViewById<TextView>(R.id.flyoutSort)
    private val spin = act.findViewById<View>(R.id.flyoutSpin)
    private val list = act.findViewById<ListView>(R.id.clipList)
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()     // hub control requests
    private val media = Executors.newSingleThreadExecutor()  // the hub allows ONE media session: thumbnails + play queue here
    private val thumbs = HashMap<String, Bitmap?>()          // shortcut: memory only, thumbnails reload after an app restart
    private val pending = HashSet<String>()
    private var items = listOf<Item>()
    private var gen = 0 // bumped on reload/close so late answers and queued thumbnails are dropped
    private var day = today()
    private var hub: Pair<String, TapoControl>? = null
    private var camName = ""
    private var camMac = ""
    private val width get() = 500 * act.resources.displayMetrics.density

    private var opening = false // the first list is still loading; the panel slides in once it is there
    private var openedAt = 0L

    val isOpen get() = opening || panel.visibility == View.VISIBLE

    fun open(hub: Pair<String, TapoControl>?, camName: String, camMac: String) {
        this.hub = hub
        this.camName = camName
        this.camMac = camMac
        day = today()
        opening = true
        openedAt = SystemClock.uptimeMillis()
        onBusy(true)
        load(remember = true)
    }

    private var selectAt = 0 // card to focus when the panel slides in (the last-watched one, else the top)

    private fun dateLabel() { date.text = "◀   ${SimpleDateFormat("EEE d MMM yyyy", Locale.US).format(day.time)}   ▶" }

    /** Slide the panel in once the list is there, but not before the spinner had 2.5 s (boss wants it seen). */
    private fun slideIn() {
        val g = gen
        val wait = maxOf(0L, 2_500L - (SystemClock.uptimeMillis() - openedAt))
        main.postDelayed({
            if (g != gen || !opening) return@postDelayed // closed meanwhile
            opening = false
            onBusy(false)
            panel.translationX = width
            panel.visibility = View.VISIBLE
            panel.animate().translationX(0f).setDuration(200).start()
            if (items.isEmpty()) date.requestFocus() else { list.requestFocus(); list.setSelection(selectAt) }
        }, wait)
    }

    fun close() {
        if (!isOpen) return
        gen++
        endConfirm(-1)
        if (opening) { opening = false; onBusy(false); return }
        panel.animate().translationX(width).setDuration(200).withEndAction { panel.visibility = View.GONE }.start()
    }

    fun hideNow() { gen++; endConfirm(-1); if (opening) onBusy(false); opening = false; panel.visibility = View.GONE }

    // ---------- hold OK on a card = "Save to this device" (boss 2026-11-17: to share it later from the phone) ----------
    private var confirmAt = -1 // the card the "Cancel / Save" question is about
    private var held = false

    /** Every key while the panel is open comes here first (also the OK release). True = handled. */
    fun key(e: KeyEvent): Boolean {
        val ok = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER
        if (ok && held) { if (e.action == KeyEvent.ACTION_UP) held = false; return true } // swallow the rest of a hold
        if (ok && e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0 && confirmAt < 0 && items.isNotEmpty() && list.hasFocus()) {
            held = true
            list.isPressed = false // the press became a hold: no click on release
            askSave(list.selectedItemPosition)
            return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_BACK && confirmAt >= 0) { if (e.action == KeyEvent.ACTION_DOWN) endConfirm(confirmAt); return true }
        return false
    }

    private fun askSave(i: Int) {
        val c = items.getOrNull(i)?.clip ?: return
        confirmAt = i
        date.visibility = View.GONE
        sort.visibility = View.GONE
        title.text = "Save this recording?"
        hintLine.text = hints("OK" to "do it", "BACK" to "back")
        // Cancel first: a stray OK must not start a long download
        list.adapter = android.widget.ArrayAdapter(act, R.layout.pos_row,
            listOf("Cancel", "Save to this device  (${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(c.start * 1000))}, ${c.end - c.start} s)"))
        list.requestFocus()
        list.setSelection(0)
    }

    /** Back to the cards ([select] = card to focus; -1 = just tidy up). */
    private fun endConfirm(select: Int) {
        if (confirmAt < 0) return
        confirmAt = -1
        date.visibility = View.VISIBLE
        sort.visibility = View.VISIBLE
        title.text = "$camName · recordings"
        hintLine.text = HINTS
        list.adapter = adapter
        if (select >= 0) { list.requestFocus(); list.setSelection(select) }
    }

    /** Downloads the whole clip from the hub (queued on [media]: the hub allows one media session) → MP4 in Movies/TapoATV. */
    private fun save(c: Clip) {
        val hubIp = hub?.first ?: return
        val name = "${camName}_${SavedFiles.stamp().format(Date(c.start * 1000))}.mp4" // the time it was filmed: the gallery shows that
        onSave("Saving recording…", false)
        media.execute {
            val ts = File(act.cacheDir, "save.ts")
            val r = runCatching {
                val cr = creds() ?: throw IOException("no password")
                var last = 0L
                var total = 0L
                TapoStream(hubIp, cr, true, c).use { s ->
                    s.connect()
                    ts.outputStream().use { out ->
                        val buf = ByteArray(64 shl 10)
                        while (true) {
                            val n = s.read(buf, 0, buf.size)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (SystemClock.uptimeMillis() - last > 500) {
                                last = SystemClock.uptimeMillis()
                                val mb = total / 1048576.0
                                main.post { onSave("Saving recording…  %.1f MB".format(mb), false) }
                            }
                        }
                    }
                }
                SavedFiles.saveMp4(act.contentResolver, ts, name)
            }.onFailure { android.util.Log.w("saved", "save recording", it) }
            ts.delete()
            main.post { onSave(r.exceptionOrNull()?.let { "⚠  Save failed: ${it.message}" } ?: "✓  Saved  ·  dock → Saved", true) }
        }
    }

    /** [remember] = the panel just opened: show the day of this camera's last-watched clip instead of today. */
    private fun load(remember: Boolean = false) {
        endConfirm(-1)
        val g = ++gen
        title.text = "$camName · recordings"
        dateLabel()
        items = emptyList()
        selectAt = 0
        adapter.notifyDataSetChanged()
        spin.visibility = View.VISIBLE
        val h = hub ?: run { spin.visibility = View.GONE; title.text = "No HomeBase found on the network"; if (opening) slideIn(); return }
        val asked = day.clone() as Calendar
        bg.execute {
            val r = runCatching {
                val e = entry(h, camName, camMac)
                val dev = e.getString("device_id")
                val mac = e.optString("mac")
                // the hub device id is only known now, so the remembered day is picked here
                val watched = prefs.getLong("watched_$dev", 0)
                val d = if (remember && watched > 0) dayOf(watched) else asked
                val from = d.timeInMillis / 1000
                val to = minOf(from + 86_399, System.currentTimeMillis() / 1000)
                val clips = h.second.clips(dev, mac, from, to)
                val events = runCatching { h.second.detections(dev, mac, from, to) }.getOrDefault(emptyList())
                // a detection belongs to the clip that starts in the same second (±2 s)
                Triple(d, watched, clips.map { c -> Item(c, events.filter { abs(it.first - c.start) <= 2 }.minByOrNull { abs(it.first - c.start) }?.second.orEmpty()) })
            }
            main.post {
                if (g != gen) return@post
                spin.visibility = View.GONE
                r.onFailure { title.text = "Recordings: ${it.message}" }
                r.onSuccess { (d, watched, list) ->
                    day = d
                    dateLabel()
                    items = if (oldestFirst) list.reversed() else list // the hub list comes newest first
                    selectAt = items.indexOfFirst { it.clip.start == watched }.coerceAtLeast(0)
                    if (list.isEmpty()) title.text = "$camName · no recordings this day"
                    adapter.notifyDataSetChanged()
                }
                if (opening) slideIn()
            }
        }
    }

    /** The camera in the hub's list: its own MAC finds it; the name is a fallback. */
    private fun entry(h: Pair<String, TapoControl>, camName: String, camMac: String): org.json.JSONObject {
        val norm = { s: String -> s.uppercase().filter { it.isLetterOrDigit() } }
        return h.second.hubCameras().let { all ->
            all.firstOrNull { norm(it.optString("mac")) == norm(camMac) } ?: all.firstOrNull { it.optString("alias") == camName }
        } ?: throw IOException("this camera does not record to the HomeBase")
    }

    // "Last watched" badge: one clip per camera, kept across restarts (prefs watched_<hub device id> = clip start)
    private val prefs = act.getSharedPreferences("tapo", Activity.MODE_PRIVATE)

    // Sort order of the cards, remembered (prefs sort_oldest)
    private var oldestFirst = prefs.getBoolean("sort_oldest", false)
    private fun sortLabel() { sort.text = "◀   ${if (oldestFirst) "Oldest first" else "Newest first"}   ▶" }
    private fun flipSort() {
        oldestFirst = !oldestFirst
        prefs.edit().putBoolean("sort_oldest", oldestFirst).apply()
        sortLabel()
        items = items.reversed()
        adapter.notifyDataSetChanged()
    }

    /** Play a clip (marks it last watched); waits for a running thumbnail, so the hub is free. */
    private fun play(clip: Clip) {
        prefs.edit().putLong("watched_${clip.devId}", clip.start).apply()
        close()
        media.execute { main.post { onPlay(clip) } }
    }

    /** Double OK: play this camera's newest recording of the last 24 h without opening the panel. */
    fun playNewest(hub: Pair<String, TapoControl>?, camName: String, camMac: String, onFail: (String) -> Unit) {
        val h = hub ?: return onFail("No HomeBase found on the network")
        bg.execute {
            val r = runCatching {
                val e = entry(h, camName, camMac)
                val now = System.currentTimeMillis() / 1000
                h.second.clips(e.getString("device_id"), e.optString("mac"), now - 86_400, now).firstOrNull() // newest first
            }
            main.post {
                r.onFailure { onFail("Recordings: ${it.message}") }
                r.onSuccess { c -> if (c == null) onFail("No recordings in the last 24 hours") else play(c) }
            }
        }
    }

    // Event codes. 4 = line crossing (boss matched it to the Tapo app); 19 = camera tampering (Sujeom: physical tamper).
    private val names = EVENT_NAMES
    private val icons = EVENT_ICONS

    /** Words only for events that have no icon (the icons say the rest and save room). */
    private fun List<Int>.label(): String =
        filter { it !in icons }.sortedDescending().joinToString(" + ") { names[it] ?: "type $it" }

    /** First frame of the clip's first 2 seconds, fetched from the hub (it keeps no pictures itself). */
    private fun loadThumb(c: Clip, key: String, g: Int) {
        val hubIp = hub?.first ?: return
        media.execute {
            if (g != gen) { main.post { pending.remove(key) }; return@execute }
            val bmp = runCatching {
                val cr = creds() ?: return@runCatching null
                val bytes = TapoStream(hubIp, cr, true, c.copy(end = c.start + 2)).use { it.connect(); it.readBytes() }
                val ts = File(act.cacheDir, "preview.ts").apply { writeBytes(bytes) }
                val ex = MediaExtractor()
                try { ex.setDataSource(ts.path); tsFrame(ex, File(act.cacheDir, "preview.mp4")) } finally { ex.release() }
            }.onFailure { android.util.Log.w("thumb", "thumbnail failed", it) }.getOrNull()
            main.post { thumbs[key] = bmp; pending.remove(key); if (g == gen) adapter.notifyDataSetChanged() }
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: act.layoutInflater.inflate(R.layout.clip_card, parent, false)
            val it = items[i]
            v.findViewById<TextView>(R.id.time).text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it.clip.start * 1000))
            v.findViewById<TextView>(R.id.info).text = "${it.clip.end - it.clip.start} s" + it.codes.label().let { l -> if (l.isEmpty()) "" else "  ·  $l" }
            val row = v.findViewById<ViewGroup>(R.id.icons)
            row.removeAllViews()
            val px = (30 * act.resources.displayMetrics.density).toInt()
            it.codes.sortedDescending().mapNotNull { c -> icons[c] }.forEach { res ->
                row.addView(ImageView(act).apply {
                    setImageResource(res)
                    contentDescription = null // the text line below says the same in words
                    layoutParams = ViewGroup.MarginLayoutParams(px, px).apply { marginEnd = px / 3 }
                })
            }
            row.visibility = if (row.childCount == 0) View.GONE else View.VISIBLE
            v.findViewById<View>(R.id.watched).visibility =
                if (prefs.getLong("watched_${it.clip.devId}", 0) == it.clip.start) View.VISIBLE else View.GONE
            val key = "${it.clip.devId}-${it.clip.start}"
            v.findViewById<ImageView>(R.id.thumb).setImageBitmap(thumbs[key])
            if (!thumbs.containsKey(key) && pending.add(key)) loadThumb(it.clip, key, gen) // only rows on screen ask
            return v
        }
    }

    init {
        date.setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { day.add(Calendar.DAY_OF_MONTH, -1); load(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (day.before(today())) { day.add(Calendar.DAY_OF_MONTH, 1); load() }; true }
                else -> false
            }
        }
        sortLabel()
        sort.setOnKeyListener { _, code, e ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) flipSort()
                    true
                }
                else -> false
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, i, _ ->
            if (confirmAt < 0) play(items[i].clip)
            else { val c = items[confirmAt].clip; val at = confirmAt; endConfirm(at); if (i == 1) save(c) }
        }
        hintLine.text = HINTS
    }

    private fun today(): Calendar = dayOf(System.currentTimeMillis() / 1000)

    /** Midnight (box time zone) of the day that holds [unix]. */
    private fun dayOf(unix: Long): Calendar = Calendar.getInstance().apply {
        timeInMillis = unix * 1000
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
}
