package za.munnik.tapoatv

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Right-hand panel with the camera's saved positions (dock "Positions"), same look as the recordings panel, so the
 * camera stays in view while it turns. Positions live ON the camera (the Tapo app shows the same list).
 * OK on a position = turn there (the panel stays open), hold OK = edit: rename / move it to this view / delete.
 */
class PositionsPanel(
    private val act: Activity,
    private val status: (String?, Boolean) -> Unit, // the one bottom-right spinner card
    private val note: (String) -> Unit,
    private val clear: (String) -> Unit, // hide the card, but only if it still shows this message
) {
    private val panel = act.findViewById<View>(R.id.posPanel)
    private val title = act.findViewById<TextView>(R.id.posTitle)
    private val hintLine = act.findViewById<TextView>(R.id.posHints)
    private val nameBox = act.findViewById<EditText>(R.id.posName)
    private val list = act.findViewById<ListView>(R.id.posList)
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val width get() = 500 * act.resources.displayMetrics.density
    private val imm get() = act.getSystemService(InputMethodManager::class.java)

    private var ctl: TapoControl? = null
    private var camName = ""
    private var presets = listOf<Pair<String, String>>() // (id, name) as the camera lists them
    private var rows = listOf<Pair<String, () -> Unit>>() // what each row says + what OK on it does
    private var onHold: ((Int) -> Unit)? = null           // hold OK on row i (list screen only)
    private var back: () -> Unit = {}
    private var held = false
    private var gen = 0 // bumped on close so late camera answers are dropped

    var isOpen = false
        private set

    init {
        list.setOnItemClickListener { _, _, i, _ -> rows.getOrNull(i)?.second?.invoke() }
        nameBox.setOnEditorActionListener { _, _, _ -> rows.firstOrNull()?.second?.invoke(); true } // keyboard Enter = Save
    }

    fun open(camName: String, ctl: TapoControl) {
        this.camName = camName
        this.ctl = ctl
        isOpen = true
        val g = ++gen
        status("Loading positions…", true)
        val at = SystemClock.uptimeMillis()
        bg.execute {
            val r = runCatching { ctl.presets() }
            main.postAtTime({ // the spinner stays at least 2.5 s (boss), then the panel slides in
                if (g != gen) return@postAtTime
                r.onSuccess {
                    status(null, false)
                    presets = it
                    panel.translationX = width
                    panel.visibility = View.VISIBLE
                    panel.animate().translationX(0f).setDuration(200).start()
                    showList(0)
                }.onFailure { isOpen = false; note("⚠  Positions: ${it.message}") }
            }, at + 2_500)
        }
    }

    fun close() {
        if (!isOpen) return
        gen++
        isOpen = false
        hideKeyboard()
        if (panel.visibility != View.VISIBLE) { status(null, false); return } // still loading
        panel.animate().translationX(width).setDuration(200).withEndAction { panel.visibility = View.GONE }.start()
    }

    fun hideNow() { if (isOpen && panel.visibility != View.VISIBLE) status(null, false); gen++; isOpen = false; hideKeyboard(); panel.visibility = View.GONE }

    /** Every key while the panel is open comes here first. True = handled. */
    fun key(e: KeyEvent): Boolean {
        val ok = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER
        if (ok && held) { if (e.action == KeyEvent.ACTION_UP) held = false; return true } // swallow the rest of a hold
        if (ok && e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0 && list.hasFocus()) { // hold OK, like the dock
            val h = onHold ?: return true
            held = true
            list.isPressed = false // the press became a hold: no click on release
            h(list.selectedItemPosition)
            return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_BACK) { if (e.action == KeyEvent.ACTION_DOWN) back(); return true }
        return false
    }

    private fun show(head: String, hint: CharSequence, r: List<Pair<String, () -> Unit>>, select: Int = 0) {
        title.text = head
        hintLine.text = hint
        rows = r
        list.adapter = ArrayAdapter(act, R.layout.pos_row, r.map { it.first })
        if (nameBox.visibility != View.VISIBLE) list.requestFocus()
        list.setSelection(select.coerceIn(0, maxOf(0, r.size - 1)))
    }

    private fun showList(select: Int) {
        nameBox.visibility = View.GONE
        onHold = { i -> if (i > 0) edit(presets[i - 1]) }
        back = { close() }
        show("$camName · positions", hints("OK" to "go there", "hold OK" to "edit", "BACK" to "close"),
            listOf("+  Save this view as a new position" to { askName(null) }) + presets.map { p -> p.second to { goTo(p) } },
            select)
    }

    private fun edit(p: Pair<String, String>) {
        onHold = null
        back = { showList(presets.indexOf(p) + 1) }
        show(p.second, hints("OK" to "do it", "BACK" to "back"), listOf(
            "Rename" to { askName(p) },
            "Move it to where the camera points now" to { save(p.second, p, turnFirst = false) },
            "Delete" to { confirmDelete(p) }))
    }

    private fun confirmDelete(p: Pair<String, String>) {
        back = { edit(p) }
        // Cancel first: a stray OK must not delete
        show("Delete ${p.second}?", hints("OK" to "do it", "BACK" to "back"), listOf(
            "Cancel" to { edit(p) },
            "Delete it (the Tapo app loses it too)" to { delete(p) }))
    }

    /** [old] null = a new position (the suggested name is a grey hint, used when left empty), else a rename. */
    private fun askName(old: Pair<String, String>?) {
        val names = presets.map { it.second }.toSet()
        val suggest = if (old == null) generateSequence(presets.size + 1) { it + 1 }.map { "Position $it" }.first { it !in names } else ""
        onHold = null
        back = { hideKeyboard(); if (old == null) showList(0) else edit(old) }
        nameBox.setText(old?.second.orEmpty())
        nameBox.hint = suggest // a hint, not text: the box's keyboard drops a select-all, so typing got glued on
        nameBox.setSelection(nameBox.text.length)
        nameBox.visibility = View.VISIBLE
        show(if (old == null) "New position" else "Rename ${old.second}", hints("▼" to "Save / Cancel", "BACK" to "back"), listOf(
            "Save" to {
                val n = nameBox.text.toString().trim().ifEmpty { suggest }
                when {
                    n.isEmpty() -> Unit
                    old == null -> save(n, null, turnFirst = false)
                    n == old.second -> back()
                    else -> save(n, old, turnFirst = true)
                }
            },
            "Cancel" to { back() }))
        nameBox.requestFocus()
        main.postDelayed({ imm.showSoftInput(nameBox, 0) }, 150)
    }

    // Panel away + "Going to …" spinner card FIRST, then the camera turns — so the boss sees it really turn (boss 2026-11-17).
    private fun goTo(p: Pair<String, String>) {
        val c = ctl ?: return
        close()
        val msg = "Going to ${p.second}…"
        status(msg, true)
        main.postDelayed({ // once the panel is out and the card is in
            bg.execute {
                val err = runCatching { c.gotoPreset(p.first) }.exceptionOrNull()
                if (err != null) main.post { note("⚠  ${p.second}: ${err.message}") }
            }
        }, 400)
        // shortcut: card stays 5 s (a typical turn) — the camera sends no "arrived" signal; lengthen if far turns outlast it
        main.postDelayed({ clear(msg) }, 5_000)
    }

    /**
     * Saves where the camera points under [name]. The camera has no rename, so a change = save new, THEN delete [old]
     * (a failed save loses nothing). A rename turns to the old spot first.
     */
    private fun save(name: String, old: Pair<String, String>?, turnFirst: Boolean) = change(if (turnFirst) "Renaming…" else "Saving position…", name) { c ->
        // shortcut: fixed 8 s for the motor to arrive (no "arrived" signal known); measure if a far turn falls short
        if (turnFirst && old != null) { c.gotoPreset(old.first); Thread.sleep(8_000) }
        c.savePreset(name)
        old?.let { c.deletePreset(it.first) }
        "✓  Saved $name"
    }

    private fun delete(p: Pair<String, String>) = change("Deleting…", null) { c -> c.deletePreset(p.first); "✓  Deleted ${p.second}" }

    /** Runs a camera change with the spinner card (min 2.5 s), then shows the fresh list (on [focusName] if given). */
    private fun change(busy: String, focusName: String?, work: (TapoControl) -> String) {
        val c = ctl ?: return
        hideKeyboard()
        nameBox.visibility = View.GONE
        onHold = null
        show(busy, "", emptyList())
        status(busy, true)
        val g = gen
        val at = SystemClock.uptimeMillis()
        bg.execute {
            val msg = runCatching { work(c) }.getOrElse { "⚠  ${it.message}" }
            val fresh = runCatching { c.presets() }.getOrNull()
            main.postAtTime({
                note(msg)
                if (g != gen) return@postAtTime
                if (fresh != null) presets = fresh
                showList(presets.indexOfFirst { it.second == focusName } + 1)
            }, at + 2_500)
        }
    }

    private fun hideKeyboard() = imm.hideSoftInputFromWindow(nameBox.windowToken, 0)
}
