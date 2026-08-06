package com.dfwriter.slate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var styler: MarkdownStyler
    private lateinit var store: DocStore

    private lateinit var root: FrameLayout
    private lateinit var editor: MarkdownEditor
    private lateinit var statusBar: LinearLayout
    private lateinit var statusLeft: TextView
    private lateinit var statusRight: TextView
    private lateinit var sheet: ListSheet
    private lateinit var settings: SettingsSheet
    private lateinit var contents: ListSheet
    private lateinit var scrim: View
    private lateinit var findBar: FindBar

    private val ui = Handler(Looper.getMainLooper())
    private var browseDir: File? = null
    private var editsSinceRefresh = 0
    private var lastFindIndex = -1
    private var statusMessage: String? = null
    private var chromeStale = false
    private var chromeUi = 1f
    private var chromeBodyPt = 0f
    private var recoveryPending = false
    private var cachedWords = 0
    private var exporting = false

    /**
     * Where autosaves are written. A whole document, fsync'd onto a removable
     * card, takes long enough that doing it on the main thread stutters the
     * sentence being typed and, on a long document, reaches the ANR watchdog.
     *
     * One thread rather than a pool, so two writes of the same document can
     * never be in flight together and the last one queued is the last one to
     * land. A daemon thread: nothing here is worth holding a process open for
     * that has otherwise finished.
     *
     * Internal rather than private so a test can stand it down and see that the
     * pause path still writes — which is the whole point of the pause path.
     */
    internal val saveIo: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "slate-save").apply { isDaemon = true }
    }

    private val autosave = object : Runnable {
        override fun run() {
            if (store.dirty) saveInBackground()
            ui.postDelayed(this, 4000)
        }
    }

    private val idleRefresh = Runnable {
        if (prefs.autoRefreshEdits > 0 && editsSinceRefresh >= prefs.autoRefreshEdits) {
            flashRefresh()
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        prefs = Prefs(this)
        Scale.init(this, prefs)
        styler = MarkdownStyler(prefs)
        store = DocStore(this, prefs)

        window.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyOrientation()
        buildUi()
        applyChrome()

        setContentView(root)

        requestStorageIfNeeded()
        restoreDocument()
        offerRecovery()
        ui.postDelayed(autosave, 4000)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Pairing or dropping the Bluetooth keyboard arrives here, and decides
        // whether the on-screen keyboard should be allowed up.
        editor.applySoftInputPolicy()
        editor.post { editor.applyMetrics() }
    }

    override fun onResume() {
        super.onResume()
        editor.applySoftInputPolicy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // This activity is singleTask, so without setIntent it keeps its very
        // first intent for good: a relaunch after the process died would hand
        // restoreDocument that stale one and reopen the file the app was first
        // started with, rather than the document last in use.
        intent?.let { setIntent(it); openFromIntent(it) }
    }

    override fun onPause() {
        super.onPause()
        // Everything from here down happens on this thread, because the process
        // can be killed the moment this method returns and queued work would go
        // with it. Drained unconditionally: a document that is already clean
        // can still have its last autosave in flight, and saveQuietly — which
        // drains as well — would not be reached to wait for it.
        //
        // Briefly, though. A pause that blocks for seconds stalls the transition
        // and can take the app down with it, and a card slow enough to need that
        // long is a card the shadow copy written below is there for: the text
        // survives either way, it is only the file that lags.
        drainSaves(PAUSE_DRAIN_MS)
        if (store.dirty) saveQuietly()
        // While the recovery prompt is unanswered the editor holds the text
        // from disk, and writing that over the shadow copy would destroy the
        // very draft being offered.
        if (!recoveryPending) store.writeScratch(editor.text.toString())
        prefs.lastCaret = editor.selectionStart
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        // After this nothing can be queued: the autosave callback has just been
        // taken off the main thread, and it is the only thing that queues. A
        // plain shutdown, so a write already running still finishes.
        saveIo.shutdown()
        super.onDestroy()
    }

    /**
     * Blocks until every autosave already handed to the save thread has reached
     * the card. The queue is FIFO and one thread deep, so an empty task at the
     * back of it can only finish once everything ahead of it has.
     *
     * Internal rather than private so a test can wait for the same thing the
     * pause path waits for, instead of guessing at a delay.
     */
    internal fun drainSaves(millis: Long = FULL_DRAIN_MS) {
        runCatching { saveIo.submit { }.get(millis, TimeUnit.MILLISECONDS) }
    }

    // ----------------------------------------------------------------- view

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        findBar = FindBar(this, prefs).apply { visibility = View.GONE }
        main.addView(
            findBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        editor = MarkdownEditor(this)
        editor.bind(prefs, styler)
        main.addView(
            editor,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        statusBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(Scale.mmInt(5f), Scale.mmInt(1.4f), Scale.mmInt(5f), Scale.mmInt(1.8f))
        }
        // The whole bar is a target for the command palette. Without it the app
        // is unusable whenever the Bluetooth keyboard is not paired, since every
        // other route in is a chord.
        statusBar.isClickable = true
        statusBar.setOnClickListener { if (sheetsOpen()) closeSheets() else showPalette() }

        statusLeft = Ui.text(this, prefs.bodyPt * 0.76f, color = Ink.RULE)
        statusRight = Ui.text(this, prefs.bodyPt * 0.76f, color = Ink.RULE)
        val fill = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        // The menu affordance sits on the side the writing hand is already on.
        if (prefs.leftHanded) {
            statusLeft.gravity = Gravity.START
            statusRight.gravity = Gravity.END
            statusBar.addView(statusLeft, fill())
            statusBar.addView(statusRight, fill())
        } else {
            statusRight.gravity = Gravity.START
            statusLeft.gravity = Gravity.END
            statusBar.addView(statusRight, fill())
            statusBar.addView(statusLeft, fill())
        }
        main.addView(Ui.divider(this, 0xFFE0E0E0.toInt()))
        main.addView(statusBar)

        root.addView(
            main,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Tapping outside a panel closes it. Escape does too, but Escape is no
        // help at all on the day the keyboard will not connect.
        scrim = View(this).apply {
            visibility = View.GONE
            isClickable = true
            setOnClickListener { closeSheets() }
        }
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        sheet = ListSheet(this, prefs).apply { visibility = View.GONE }
        settings = SettingsSheet(this, prefs).apply { visibility = View.GONE }
        contents = ListSheet(this, prefs).apply { visibility = View.GONE }
        root.addView(sheet, sheetParams())
        root.addView(settings, sheetParams())
        root.addView(contents, drawerParams())

        wireCallbacks()
        updateStatus()
        // The sizes this chrome was just built from. A later change compares
        // against them to decide whether a rebuild is owed.
        chromeUi = Scale.ui
        chromeBodyPt = prefs.bodyPt
    }

    /**
     * A drawer down one edge rather than a panel in the middle of the page. It
     * stays open while chapters are picked, so the document stays visible
     * beside it — the whole point of having it for a long piece of writing.
     *
     * On the side the writing hand is already on, like the rest of the chrome.
     */
    private fun drawerParams(): FrameLayout.LayoutParams {
        val screen = resources.displayMetrics.widthPixels
        val width = Scale.mmInt(64f)
            .coerceAtLeast((screen * 0.30f).toInt())
            .coerceAtMost((screen * 0.55f).toInt())
        val lp = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
        lp.gravity = if (prefs.leftHanded) Gravity.START else Gravity.END
        return lp
    }

    private fun sheetParams(): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        val side = Scale.mmInt(14f)
        val vert = Scale.mmInt(10f)
        lp.setMargins(side, vert, side, vert)
        return lp
    }

    private fun wireCallbacks() {
        editor.onEdit = {
            store.dirty = true
            editsSinceRefresh++
            // Not recounted here: that is the per-keystroke pass this avoids.
            ui.removeCallbacks(recountSoon)
            ui.postDelayed(recountSoon, RECOUNT_MS)
            updateStatus()
            ui.removeCallbacks(idleRefresh)
            ui.postDelayed(idleRefresh, 1400)
        }
        editor.onCaretMoved = { updateStatus() }
        editor.onLinkTapped = { openLink(it) }

        sheet.onDismiss = { closeSheets() }
        settings.onDismiss = { closeSheets() }
        contents.onDismiss = { closeSheets() }
        settings.onChanged = {
            Scale.init(this, prefs)
            editor.applyMetrics()
            applyOrientation()
            applyChrome()
            updateStatus()
            // The status bar and the panels bake their sizes in when they are
            // built, so a size change — the interface scale or the body text,
            // both of which they measure themselves against — only reaches them
            // through a rebuild. Compared against what the chrome was built
            // from, since the row has already applied its change by now.
            // Deferred to when the panel closes, so stepping the value does not
            // tear the panel down under the user's finger.
            if (Scale.ui != chromeUi || prefs.bodyPt != chromeBodyPt) chromeStale = true
        }

        findBar.onDismiss = { findBar.hide(); editor.requestFocus() }
        findBar.onFind = { q, fwd -> findNext(q, fwd) }
        findBar.onReplaceOne = { q, r -> replaceOne(q, r) }
        findBar.onReplaceAll = { q, r -> replaceAll(q, r) }
    }

    private fun applyChrome() {
        // A flash needs somewhere to land even when the bar is switched off, or
        // an error would be swallowed in silence; see flash().
        statusBar.visibility =
            if (prefs.showStatusBar || statusMessage != null) View.VISIBLE else View.GONE
        @Suppress("DEPRECATION")
        root.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
    }

    private fun setHandedness(left: Boolean) {
        prefs.leftHanded = left
        // The status bar and the stepper rows are built mirrored, so the view
        // tree has to be rebuilt for the change to show.
        flash(if (left) "Left-handed layout" else "Right-handed layout")
        rebuildChromeSoon()
    }

    private fun setOrientation(o: Orientation) {
        prefs.orientation = o
        applyOrientation()
        flash("Screen: ${o.name.lowercase()}")
    }

    private fun applyOrientation() {
        requestedOrientation = when (prefs.orientation) {
            Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // USER, not UNSPECIFIED: it follows the device's own auto-rotate
            // switch, so turning the Manta over turns the page over with it.
            Orientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_USER
        }
    }

    // ------------------------------------------------------------- document

    private fun requestStorageIfNeeded() {
        val need = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(
        code: Int, permissions: Array<out String>, results: IntArray
    ) {
        super.onRequestPermissionsResult(code, permissions, results)
        // The library root is chosen lazily, so a late grant just widens where
        // documents can live; nothing needs re-opening.
        updateStatus()
    }

    private fun restoreDocument() {
        intent?.let { if (openFromIntent(it)) return }

        val last = prefs.lastFile
        if (last.isNotEmpty()) {
            val f = File(last)
            if (f.isFile && f.canRead()) {
                loadInto(f)
                editor.setSelection(prefs.lastCaret.coerceIn(0, editor.text.length))
                return
            }
        }
        val welcome = File(store.libraryRoot(), "Welcome to Slate.md")
        if (!welcome.exists()) {
            runCatching { welcome.writeText(WELCOME, Charsets.UTF_8) }
        }
        if (welcome.exists()) loadInto(welcome) else newDocument()
    }

    /**
     * If the last run ended without its text reaching disk — a crash, or a save
     * that failed — the shadow copy is still in private storage. Offer it rather
     * than quietly discarding the difference.
     */
    private fun offerRecovery() {
        val recovered = store.recoverableText(editor.text.toString()) ?: return
        val onDisk = editor.text.toString()

        sheet.configure(
            "Unsaved text was recovered",
            "",
            listOf(
                ListSheet.Item(
                    "Restore the recovered text",
                    "${DocStore.countWords(recovered)} words",
                    payload = RECOVER_RESTORE
                ),
                ListSheet.Item(
                    "Keep what is on disk",
                    "${DocStore.countWords(onDisk)} words",
                    payload = RECOVER_DISCARD
                )
            ),
            ""
        )
        sheet.onFreeText = null
        // Until this is answered the shadow copy is the only place the draft
        // exists, so nothing may write over it. Dismissing the prompt without
        // choosing leaves it alone too, and the offer comes back next start.
        recoveryPending = true
        sheet.onPick = { item ->
            closeSheets()
            recoveryPending = false
            if (item.payload == RECOVER_RESTORE) {
                editor.setText(recovered)
                editor.restyleNow()
                editor.setSelection(recovered.length.coerceIn(0, editor.text.length))
                editor.clearHistory()
                store.dirty = true
                if (saveQuietly()) flash("Recovered text restored and saved")
            } else {
                store.clearScratch()
                flash("Recovered text discarded")
            }
            updateStatus()
        }
        scrim.visibility = View.VISIBLE
        sheet.show()
    }

    private fun openFromIntent(i: Intent): Boolean {
        val uri: Uri = i.data ?: return false
        val f = when (uri.scheme) {
            "file" -> uri.path?.let(::File)
            else -> null
        }
        if (f != null && f.isFile) {
            loadInto(f); return true
        }
        // Content URIs cannot be written back in place, so the text is copied
        // into the library and edited there rather than silently going nowhere.
        return runCatching {
            val body = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return false
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.md"
            val dest = importTarget(DocStore.ensureExt(name), body)
            if (!dest.exists()) dest.writeText(body, Charsets.UTF_8)
            loadInto(dest)
            true
        }.getOrDefault(false)
    }

    /**
     * Where an imported copy lands. Handing the same document over a second time
     * must not overwrite the copy already edited here, so a name in use takes a
     * suffix the way [DocStore.newFile] does — unless what is already there is
     * identical, in which case that file is simply reopened rather than
     * duplicated on every visit from the file manager.
     */
    private fun importTarget(fileName: String, body: String): File {
        val dir = store.libraryRoot()
        val stem = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "md")
        var f = File(dir, fileName)
        var n = 2
        while (f.exists()) {
            if (f.isFile && runCatching { store.read(f) }.getOrNull() == body) return f
            f = File(dir, "$stem-$n.$ext")
            n++
        }
        return f
    }

    private fun loadInto(f: File) {
        val body = runCatching { store.open(f) }.getOrElse {
            flash("Could not read ${f.name}")
            return
        }
        // Relative image paths are resolved against the document's own folder,
        // and a file that was missing last time may exist now.
        styler.documentDir = f.parentFile
        ImageCache.retryBroken()
        editor.setText(body)
        editor.restyleNow()
        editor.setSelection(0)
        editor.requestFocus()
        editor.clearHistory()
        editsSinceRefresh = 0
        // setText runs the change listener, which flags the document dirty even
        // though loading is not an edit. Clear it, or every open would rewrite
        // the file on the next autosave tick.
        store.dirty = false
        // A document just opened gets its count now rather than in a moment.
        ui.removeCallbacks(recountSoon)
        recount()
        updateStatus()
    }

    private fun newDocument() {
        val f = store.createAndOpen(store.libraryRoot())
        editor.setText("")
        editor.restyleNow()
        editor.requestFocus()
        editor.clearHistory()
        store.dirty = false
        updateStatus()
        flash(
            if (f != null) "New document: ${f.name}"
            else "Could not create a file — check storage permission"
        )
    }

    /**
     * The file a save should land in, made if the document has never had one.
     * Null when there is nowhere to write at all, in which case the text is
     * kept in private storage and the document is left dirty rather than
     * pretending it was saved.
     */
    private fun saveTarget(body: String): File? {
        store.current?.let { return it }
        val made = store.createAndOpen(store.libraryRoot(), firstHeading(body))
        if (made == null) {
            store.writeScratch(body)
            flash("Save failed — check storage permission")
        }
        return made
    }

    /** Saves on this thread, for the places that cannot outlive a wait. */
    private fun saveQuietly(): Boolean {
        // Anything already on its way to the card carries older text, and a
        // save made here must be the last word rather than the first.
        drainSaves()
        val body = editor.text.toString()
        saveTarget(body) ?: return false
        if (store.save(body) == null) {
            store.writeScratch(body)
            flash("Save failed — check storage permission")
            return false
        }
        updateStatus()
        return true
    }

    /**
     * The autosave path. The text is snapshotted here, on the main thread, and
     * only the writing goes to [saveIo]; everything the app remembers about the
     * document is still changed here, where it is read from.
     */
    private fun saveInBackground() {
        val body = editor.text.toString()
        val f = saveTarget(body) ?: return
        // Cleared against the snapshot rather than when the write lands. An
        // edit made while the write is in flight is not in the text being
        // written, so it has to leave the document dirty for the next tick.
        store.dirty = false
        updateStatus()
        val gen = store.generation()
        saveIo.execute {
            val result = store.writeThroughResult(f, body, gen)
            // Only a real failure is worth keeping and worth reporting. A stale
            // write belongs to a document that has since been renamed or
            // deleted: its text is not the open document's, so shadowing it
            // would overwrite the live draft with another file's words, and
            // saying "save failed" would be a lie about a document that is fine.
            if (result == DocStore.WriteResult.FAILED) {
                // Text that did not reach the card is kept in private storage,
                // the same as a failed save on the main thread does — and
                // written from this thread, so the main one never waits on a
                // failing card.
                store.writeScratchFor(f.absolutePath, body)
            }
            ui.post {
                // This thread outlives the activity, so a message posted from
                // it can arrive after an onDestroy that could not cancel it.
                if (isDestroyed || isFinishing) return@post
                if (result == DocStore.WriteResult.FAILED) {
                    store.dirty = true
                    flash("Save failed — check storage permission")
                    updateStatus()
                }
            }
        }
    }

    private fun firstHeading(body: String): String? {
        for (line in body.lineSequence()) {
            val m = Regex("^#{1,6}\\s+(.*)$").find(line.trim())
            if (m != null) return m.groupValues[1]
            if (line.isNotBlank()) return line.trim().take(50)
        }
        return null
    }

    // --------------------------------------------------------------- panels

    /** Hides the panels without triggering a pending rebuild. */
    private fun hidePanels() {
        sheet.hide()
        settings.hide()
        contents.hide()
        scrim.visibility = View.GONE
    }

    private fun closeSheets() {
        hidePanels()
        editor.requestFocus()
        if (chromeStale) {
            chromeStale = false
            scheduleChromeRebuild()
        }
    }

    private fun sheetsOpen() =
        sheet.visibility == View.VISIBLE || settings.visibility == View.VISIBLE ||
                contents.visibility == View.VISIBLE

    private fun showPalette() {
        settings.hide()
        val items = commands().map {
            ListSheet.Item(title = it.title, keys = it.keys, payload = it)
        }
        scrim.visibility = View.VISIBLE
        sheet.configure("Commands", "Type a command…", items)
        sheet.onPick = { item ->
            closeSheets()
            (item.payload as? Cmd)?.run?.invoke()
        }
        sheet.onFreeText = null
        sheet.show()
    }

    private fun showFiles(dir: File = browseDir ?: store.libraryRoot()) {
        settings.hide()
        browseDir = dir
        val items = ArrayList<ListSheet.Item>()
        dir.parentFile?.let {
            if (it.canRead()) items.add(ListSheet.Item("⌃ ${it.name.ifEmpty { "/" }}", "up", payload = it))
        }
        for (e in store.list(dir)) {
            items.add(
                if (e.isDir) ListSheet.Item("▸ ${e.file.name}", "folder", payload = e.file)
                else ListSheet.Item(e.file.name, humanSize(e.file.length()), payload = e.file)
            )
        }
        scrim.visibility = View.VISIBLE
        sheet.configure(
            "Files · ${dir.absolutePath}",
            "Filter · a new name, then Shift Enter to create it",
            items
        )
        sheet.onPick = { item ->
            val f = item.payload as? File
            when {
                f == null -> Unit
                f.isDirectory -> showFiles(f)
                else -> { closeSheets(); openWithSave(f) }
            }
        }
        sheet.onFreeText = { name ->
            closeSheets()
            val target = browseDir ?: store.libraryRoot()
            val existing = File(target, DocStore.ensureExt(name))
            if (existing.exists()) {
                openWithSave(existing)
            } else {
                if (store.dirty) saveQuietly()
                val made = store.createNamed(target, name)
                if (made != null) {
                    loadInto(made)
                    flash("Created ${made.name}")
                } else {
                    flash("Could not create $name here")
                }
            }
        }
        sheet.show()
    }

    private fun showOutline() {
        settings.hide()
        val heads = MarkdownStyler.outline(editor.text)
        val items = heads.map {
            ListSheet.Item(it.title, "H${it.level}", indent = it.level - 1, payload = it.offset)
        }
        scrim.visibility = View.VISIBLE
        sheet.configure("Outline", "Jump to a heading…", items)
        sheet.onPick = { item ->
            closeSheets()
            (item.payload as? Int)?.let { off ->
                editor.setSelection(off.coerceIn(0, editor.text.length))
                editor.post { editor.centreCaret() }
            }
        }
        sheet.onFreeText = null
        sheet.show()
    }

    /**
     * The table of contents, as a drawer that stays open. Picking a heading
     * moves the document behind it and leaves the list where it is, so a book
     * can be walked chapter by chapter without reopening the panel each time.
     */
    private fun showContents() {
        if (contents.visibility == View.VISIBLE) { closeSheets(); return }
        sheet.hide()
        settings.hide()

        val heads = MarkdownStyler.outline(editor.text)
        val items = heads.map {
            ListSheet.Item(it.title, "", indent = it.level - 1, payload = it.offset)
        }
        scrim.visibility = View.VISIBLE
        contents.configure(
            if (heads.isEmpty()) "Contents" else "Contents · ${heads.size}",
            "Filter…",
            items,
            "No headings yet. Start a line with # to make one."
        )
        contents.onFreeText = null
        contents.onPick = { item ->
            (item.payload as? Int)?.let { off ->
                editor.setSelection(off.coerceIn(0, editor.text.length))
                editor.post { editor.centreCaret() }
            }
            // Deliberately still open: the next chapter is one more tap away.
        }
        contents.show()
    }

    private fun showSettings() {
        sheet.hide()
        scrim.visibility = View.VISIBLE
        settings.setRows(settingRows())
        settings.show()
    }

    private fun settingRows(): List<SettingsSheet.Row> = listOf(
        SettingsSheet.Row(
            "Interface scale",
            { "${Math.round(Scale.ui * 100)}%" },
            { d -> Scale.setUiScale(prefs, Scale.ui + d * Scale.UI_STEP) },
            "everything, at once"
        ),
        SettingsSheet.Row(
            "Text size",
            { "${"%.1f".format(prefs.bodyPt)} pt" },
            { d -> prefs.bodyPt = (prefs.bodyPt + d * 0.5f).coerceIn(7f, 30f) }
        ),
        SettingsSheet.Row(
            "Line spacing",
            { "%.2f".format(prefs.lineSpacing) },
            { d -> prefs.lineSpacing = (prefs.lineSpacing + d * 0.05f).coerceIn(1.0f, 2.4f) }
        ),
        SettingsSheet.Row(
            "Line length",
            { if (prefs.measureChars <= 0) "full width" else "${prefs.measureChars} chars" },
            { d -> prefs.measureChars = (prefs.measureChars + d * 4).coerceIn(0, 160) }
        ),
        SettingsSheet.Row(
            "Typeface",
            { prefs.typeface.name.lowercase() },
            { d ->
                val v = SerifChoice.values()
                prefs.typeface = v[((prefs.typeface.ordinal + d) % v.size + v.size) % v.size]
            }
        ),
        SettingsSheet.Row(
            "Focus mode",
            { onOff(prefs.focusMode) },
            { prefs.focusMode = !prefs.focusMode; editor.restyleNow() },
            "dim all but this paragraph"
        ),
        SettingsSheet.Row(
            "Typewriter mode",
            { onOff(prefs.typewriterMode) },
            { prefs.typewriterMode = !prefs.typewriterMode; editor.applyMetrics() },
            "keep the caret centred"
        ),
        SettingsSheet.Row(
            "Hide syntax markers",
            { onOff(prefs.hideMarkers) },
            { prefs.hideMarkers = !prefs.hideMarkers; editor.restyleNow() }
        ),
        SettingsSheet.Row(
            "Show raw source",
            { onOff(prefs.sourceMode) },
            { prefs.sourceMode = !prefs.sourceMode; editor.restyleNow() }
        ),
        SettingsSheet.Row(
            "Orientation",
            { prefs.orientation.name.lowercase() },
            { d ->
                val v = Orientation.values()
                prefs.orientation = v[((prefs.orientation.ordinal + d) % v.size + v.size) % v.size]
            }
        ),
        SettingsSheet.Row(
            "Status bar",
            { onOff(prefs.showStatusBar) },
            { prefs.showStatusBar = !prefs.showStatusBar },
            "tap it for the palette"
        ),
        SettingsSheet.Row(
            "Handedness",
            { if (prefs.leftHanded) "left" else "right" },
            // Must go through setHandedness: the status bar is mirrored at build
            // time, so flipping the flag alone would leave the stepper rows and
            // the status bar disagreeing until something else rebuilt the tree.
            { setHandedness(!prefs.leftHanded) },
            "which side the controls sit on"
        ),
        SettingsSheet.Row(
            "On-screen keyboard",
            { prefs.softKeyboard.name.lowercase() },
            { d ->
                val v = SoftKeyboard.values()
                prefs.softKeyboard =
                    v[((prefs.softKeyboard.ordinal + d) % v.size + v.size) % v.size]
                editor.applySoftInputPolicy()
            },
            "auto hides it for Bluetooth"
        ),
        SettingsSheet.Row(
            "Auto screen refresh",
            { if (prefs.autoRefreshEdits <= 0) "off" else "${prefs.autoRefreshEdits} edits" },
            { d -> prefs.autoRefreshEdits = (prefs.autoRefreshEdits + d * 100).coerceIn(0, 3000) },
            "clears E Ink ghosting"
        ),
        SettingsSheet.Row(
            "Panel",
            { Scale.describe() },
            { },
            "what sizing is based on"
        )
    )

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    // ------------------------------------------------------------- commands

    private class Cmd(val title: String, val keys: String, val run: () -> Unit)

    private fun commands(): List<Cmd> = listOf(
        Cmd("New document", "Ctrl N") { if (store.dirty) saveQuietly(); newDocument() },
        Cmd("Open file…", "Ctrl O") { showFiles() },
        Cmd("Save", "Ctrl S") { if (saveQuietly()) flash("Saved") },
        Cmd("Rename document…", "") { promptRename() },
        Cmd("Delete document", "") { promptDelete() },
        Cmd("Set library folder to this folder", "") {
            // Before the file list has been opened there is no folder this could
            // mean, and doing nothing at all would read as having worked.
            val d = browseDir
            if (d == null) flash("Open the file list first — Ctrl O")
            else { store.setLibraryRoot(d); flash("Library: ${d.name}") }
        },
        Cmd("Undo", "Ctrl Z") { if (!editor.undo()) flash("Nothing to undo") },
        Cmd("Redo", "Ctrl Shift Z") { if (!editor.redo()) flash("Nothing to redo") },
        Cmd("Outline", "Ctrl Shift O") { showOutline() },
        Cmd("Table of contents", "Ctrl T") { showContents() },
        Cmd("Find…", "Ctrl F") { openFind(false) },
        Cmd("Replace…", "Ctrl H") { openFind(true) },
        Cmd("Settings", "Ctrl ,") { showSettings() },

        Cmd("Bold", "Ctrl B") { editor.toggleWrap("**") },
        Cmd("Italic", "Ctrl I") { editor.toggleWrap("*") },
        Cmd("Inline code", "Ctrl E") { editor.toggleWrap("`") },
        Cmd("Strikethrough", "Ctrl Shift D") { editor.toggleWrap("~~") },
        Cmd("Highlight", "") { editor.toggleWrap("==") },
        Cmd("Link", "Ctrl K") { editor.toggleWrap("[", "](url)") },
        Cmd("Open the link under the caret", "Ctrl Enter") {
            val target = editor.linkAtCaret()
            if (target.isNullOrBlank()) flash("The caret is not in a link") else openLink(target)
        },
        Cmd("Heading 1", "Ctrl 1") { editor.setHeading(1) },
        Cmd("Heading 2", "Ctrl 2") { editor.setHeading(2) },
        Cmd("Heading 3", "Ctrl 3") { editor.setHeading(3) },
        Cmd("Paragraph", "Ctrl 0") { editor.setHeading(0) },
        Cmd("Bullet list", "Ctrl Shift L") { editor.togglePrefix("- ") },
        Cmd("Numbered list", "Ctrl Shift N") { editor.togglePrefix("1. ") },
        Cmd("Task list", "Ctrl Shift T") { editor.togglePrefix("- [ ] ") },
        Cmd("Blockquote", "Ctrl Shift Q") { editor.togglePrefix("> ") },
        Cmd("Code block", "Ctrl Shift K") { editor.insertBlock("```\n\n```\n", 5) },
        Cmd("Horizontal rule", "Ctrl Shift H") { editor.insertBlock("\n---\n\n") },
        Cmd("Table", "Ctrl Shift B") {
            editor.insertBlock("\n| A | B |\n| --- | --- |\n|  |  |\n\n", 8)
        },

        Cmd("Toggle focus mode", "F8") {
            prefs.focusMode = !prefs.focusMode; editor.restyleNow()
            flash("Focus ${onOff(prefs.focusMode)}")
        },
        Cmd("Toggle typewriter mode", "F9") {
            prefs.typewriterMode = !prefs.typewriterMode; editor.applyMetrics()
            flash("Typewriter ${onOff(prefs.typewriterMode)}")
        },
        Cmd("Toggle raw source", "Ctrl /") {
            prefs.sourceMode = !prefs.sourceMode; editor.restyleNow()
            flash("Source ${onOff(prefs.sourceMode)}")
        },
        Cmd("Toggle status bar", "F11") {
            prefs.showStatusBar = !prefs.showStatusBar
            applyChrome()
            // Nothing is written into the bar while it is hidden, so without
            // this a bar brought back shows whatever it last held until the
            // next keystroke — including a count from before it went away.
            recount()
            updateStatus()
        },
        Cmd("Rotate screen", "Ctrl Shift R") {
            val v = Orientation.values()
            prefs.orientation = v[(prefs.orientation.ordinal + 1) % v.size]
            applyOrientation()
            flash("Screen: ${prefs.orientation.name.lowercase()}")
        },
        Cmd("Switch to left-handed layout", "") { setHandedness(true) },
        Cmd("Switch to right-handed layout", "") { setHandedness(false) },
        Cmd("Screen: landscape", "") { setOrientation(Orientation.LANDSCAPE) },
        Cmd("Screen: portrait", "") { setOrientation(Orientation.PORTRAIT) },
        Cmd("Screen: follow the system", "") { setOrientation(Orientation.AUTO) },
        Cmd("Show the on-screen keyboard", "") {
            prefs.softKeyboard = SoftKeyboard.ALWAYS
            editor.applySoftInputPolicy()
            editor.showIme()
            flash("On-screen keyboard: always")
        },
        Cmd("Hide the on-screen keyboard", "") {
            prefs.softKeyboard = SoftKeyboard.NEVER
            editor.applySoftInputPolicy()
            flash("On-screen keyboard: never")
        },
        Cmd("On-screen keyboard follows the hardware", "") {
            prefs.softKeyboard = SoftKeyboard.AUTO
            editor.applySoftInputPolicy()
            flash("On-screen keyboard: auto")
        },
        Cmd("Bigger interface", "Ctrl =") { nudgeScale(1) },
        Cmd("Smaller interface", "Ctrl -") { nudgeScale(-1) },
        Cmd("Reset interface size", "Ctrl Shift 0") {
            Scale.setUiScale(prefs, 1f)
            editor.applyMetrics()
            flash("Scale 100%")
            rebuildChromeSoon()
        },
        Cmd("Refresh screen", "Ctrl R") { flashRefresh() },
        Cmd("Word count", "Ctrl W") {
            val w = DocStore.countWords(editor.text)
            flash("$w words · ${editor.text.length} characters · ${readingTime(w)}")
        },
        Cmd("Export to HTML", "Ctrl Shift M") { exportHtml() },
        Cmd("Export to PDF", "Ctrl Shift P") { exportPdf() }
    )

    private fun nudgeScale(dir: Int) {
        Scale.setUiScale(prefs, Scale.ui + dir * Scale.UI_STEP)
        flash("Interface ${Math.round(Scale.ui * 100)}%")
        rebuildChromeSoon()
    }

    private val rebuildChromeTask = Runnable { rebuildChrome() }

    /**
     * Rebuilding swaps the whole view tree, so it must not happen while the key
     * event that triggered it is still being dispatched through that tree.
     * Coalesced, because holding Ctrl+= would otherwise rebuild and restyle the
     * document once per repeat.
     */
    private fun scheduleChromeRebuild() {
        ui.removeCallbacks(rebuildChromeTask)
        ui.post(rebuildChromeTask)
    }

    /**
     * As [scheduleChromeRebuild], but never while a panel is open: the rebuild
     * would replace the panel under the finger that asked for it. Held over to
     * [closeSheets], the same way a change made from the settings sheet is.
     */
    private fun rebuildChromeSoon() {
        if (sheetsOpen()) chromeStale = true else scheduleChromeRebuild()
    }

    /** Status bar and panels bake in point sizes, so a scale change rebuilds them. */
    private fun rebuildChrome() {
        val caret = editor.selectionStart
        val body = editor.text.toString()
        val scrollY = editor.scrollY
        val wasDirty = store.dirty
        // The find bar is part of the tree being replaced, so an open search
        // would otherwise vanish along with whatever had been typed into it.
        val findWasOpen = findBar.visibility == View.VISIBLE
        val findQuery = findBar.queryText()
        val history = editor.snapshotHistory()

        root.removeAllViews()
        buildUi()
        setContentView(root)
        applyChrome()
        editor.setText(body)
        editor.restyleNow()
        editor.setSelection(caret.coerceIn(0, editor.text.length))
        editor.requestFocus()
        editor.post { editor.scrollTo(0, scrollY) }
        store.dirty = wasDirty
        // After setText, which itself records an edit that never happened.
        editor.restoreHistory(history)
        ui.removeCallbacks(recountSoon)
        recount()
        updateStatus()

        if (findWasOpen) {
            findBar.setQuery(findQuery)
            findBar.show()
        }
    }

    // ------------------------------------------------------------ shortcuts

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (ev.action == KeyEvent.ACTION_DOWN && handleShortcut(ev)) return true
        return super.dispatchKeyEvent(ev)
    }

    private fun handleShortcut(ev: KeyEvent): Boolean {
        val code = ev.keyCode
        // A held chord auto-repeats about twenty times a second. Only the size
        // nudges are meant to be leant on; anything else would run once per
        // repeat — twenty new documents, or twenty export threads writing the
        // same PDF over each other. The repeat is dropped at each command
        // rather than up front, so that a Ctrl chord this app does not claim,
        // Ctrl with an arrow key say, still repeats in the editor.
        val held = ev.repeatCount != 0

        if (!ev.isCtrlPressed) {
            if (code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BACK) {
                if (sheetsOpen()) { closeSheets(); return true }
                if (findBar.visibility == View.VISIBLE) {
                    findBar.hide(); editor.requestFocus(); return true
                }
                return false
            }
            // Leave every other bare key to whichever view has focus.
            if (sheetsOpen()) return false
            return when (code) {
                KeyEvent.KEYCODE_F8 -> { if (!held) runCmd("Toggle focus mode"); true }
                KeyEvent.KEYCODE_F9 -> { if (!held) runCmd("Toggle typewriter mode"); true }
                KeyEvent.KEYCODE_F11 -> { if (!held) runCmd("Toggle status bar"); true }
                KeyEvent.KEYCODE_F5 -> { if (!held) flashRefresh(); true }
                else -> false
            }
        }

        // Ctrl chords stay global so they work from a panel too.
        val shift = ev.isShiftPressed
        val title: String? = when (code) {
            KeyEvent.KEYCODE_N -> if (shift) "Numbered list" else "New document"
            KeyEvent.KEYCODE_O -> if (shift) "Outline" else "Open file…"
            KeyEvent.KEYCODE_S -> "Save"
            KeyEvent.KEYCODE_P -> if (shift) "Export to PDF" else null
            KeyEvent.KEYCODE_M -> if (shift) "Export to HTML" else null
            KeyEvent.KEYCODE_B -> if (shift) "Table" else "Bold"
            KeyEvent.KEYCODE_I -> "Italic"
            KeyEvent.KEYCODE_E -> "Inline code"
            KeyEvent.KEYCODE_D -> if (shift) "Strikethrough" else null
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                "Open the link under the caret"
            KeyEvent.KEYCODE_K -> if (shift) "Code block" else "Link"
            KeyEvent.KEYCODE_L -> if (shift) "Bullet list" else null
            KeyEvent.KEYCODE_T -> if (shift) "Task list" else "Table of contents"
            KeyEvent.KEYCODE_Q -> if (shift) "Blockquote" else null
            KeyEvent.KEYCODE_H -> if (shift) "Horizontal rule" else "Replace…"
            KeyEvent.KEYCODE_R -> if (shift) "Rotate screen" else "Refresh screen"
            KeyEvent.KEYCODE_Z -> if (shift) "Redo" else "Undo"
            KeyEvent.KEYCODE_Y -> "Redo"
            KeyEvent.KEYCODE_F -> "Find…"
            KeyEvent.KEYCODE_W -> "Word count"
            KeyEvent.KEYCODE_COMMA -> "Settings"
            KeyEvent.KEYCODE_SLASH -> "Toggle raw source"
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS -> "Bigger interface"
            KeyEvent.KEYCODE_MINUS -> "Smaller interface"
            KeyEvent.KEYCODE_1 -> "Heading 1"
            KeyEvent.KEYCODE_2 -> "Heading 2"
            KeyEvent.KEYCODE_3 -> "Heading 3"
            KeyEvent.KEYCODE_0 -> if (shift) "Reset interface size" else "Paragraph"
            else -> null
        }

        if (code == KeyEvent.KEYCODE_P && !shift) {
            if (!held) { if (sheetsOpen()) closeSheets() else showPalette() }
            return true
        }
        if (code == KeyEvent.KEYCODE_G) {
            if (!held) {
                val q = findBar.queryText()
                if (q.isNotEmpty()) findNext(q, !shift) else openFind(false)
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_4 || code == KeyEvent.KEYCODE_5 || code == KeyEvent.KEYCODE_6) {
            if (!held) editor.setHeading(code - KeyEvent.KEYCODE_0)
            return true
        }

        if (title == null) return false
        if (held && title != "Bigger interface" && title != "Smaller interface") return true
        runCmd(title)
        return true
    }

    private fun runCmd(title: String) {
        commands().firstOrNull { it.title == title }?.run?.invoke()
    }

    // ---------------------------------------------------------------- find

    private fun openFind(withReplace: Boolean) {
        // hidePanels rather than closeSheets: a deferred chrome rebuild would
        // replace the find bar moments after it was shown.
        hidePanels()
        findBar.show()
        val sel = editor.text.subSequence(
            min(editor.selectionStart, editor.selectionEnd),
            max(editor.selectionStart, editor.selectionEnd)
        ).toString()
        if (sel.isNotEmpty() && sel.length < 80) findBar.setQuery(sel)
        findBar.setStatus(if (withReplace) "Tab to the replace field" else "")
    }

    private fun findNext(q: String, forward: Boolean) {
        val hay = editor.text.toString()
        if (q.isEmpty() || hay.isEmpty()) return
        val from = if (forward) max(editor.selectionEnd, 0) else max(editor.selectionStart - 1, 0)
        var idx = if (forward) hay.indexOf(q, from, ignoreCase = true)
        else hay.lastIndexOf(q, from, ignoreCase = true)
        if (idx < 0) {
            idx = if (forward) hay.indexOf(q, 0, ignoreCase = true)
            else hay.lastIndexOf(q, hay.length, ignoreCase = true)
            findBar.setStatus(if (idx >= 0) "wrapped" else "no match")
        } else {
            findBar.setStatus("")
        }
        if (idx < 0) return
        lastFindIndex = idx
        editor.setSelection(idx, idx + q.length)
        editor.post { editor.centreCaret() }
    }

    private fun replaceOne(q: String, r: String) {
        if (q.isEmpty()) return
        val s = min(editor.selectionStart, editor.selectionEnd)
        val e = max(editor.selectionStart, editor.selectionEnd)
        val sel = editor.text.subSequence(s, e).toString()
        if (sel.equals(q, ignoreCase = true)) {
            editor.text.replace(s, e, r)
            editor.setSelection(s + r.length)
        }
        findNext(q, true)
    }

    private fun replaceAll(q: String, r: String) {
        if (q.isEmpty()) return
        val body = editor.text.toString()
        var count = 0
        var i = body.indexOf(q, 0, ignoreCase = true)
        while (i >= 0) { count++; i = body.indexOf(q, i + q.length, ignoreCase = true) }
        if (count == 0) { findBar.setStatus("no match"); return }
        val caret = editor.selectionStart
        editor.setText(body.replace(q, r, ignoreCase = true))
        editor.restyleNow()
        editor.setSelection(caret.coerceIn(0, editor.text.length))
        findBar.setStatus("replaced $count")
    }

    // -------------------------------------------------------------- exports

    private fun exportHtml() {
        val name = (store.current?.nameWithoutExtension ?: "document")
        val out = File(exportDir(), "$name.html")
        runCatching {
            out.parentFile?.mkdirs()
            out.writeText(Exporter.toHtml(editor.text.toString(), name), Charsets.UTF_8)
        }.onSuccess { flash("HTML → ${out.absolutePath}") }
            .onFailure { flash("Export failed: ${it.message}") }
    }

    private fun exportPdf() {
        // A second run would be drawing into the same file as the first.
        if (exporting) { flash("Still building the last PDF…"); return }
        val name = (store.current?.nameWithoutExtension ?: "document")
        val out = File(exportDir(), "$name.pdf")
        val body = editor.text.toString()
        flash("Building PDF…")
        exporting = true
        // Laying out and drawing every page is far too slow for the main thread
        // on an RK3566; a long document would trip the ANR watchdog.
        Thread {
            val result = runCatching { Exporter.toPdf(body, prefs, out, name) }
            ui.post {
                exporting = false
                // The thread outlives the activity, and a message posted from it
                // after onDestroy is one that onDestroy could not have cancelled.
                if (isDestroyed || isFinishing) return@post
                result.onSuccess { flash("PDF → ${out.absolutePath}") }
                    .onFailure { flash("Export failed: ${it.message}") }
            }
        }.start()
    }

    private fun exportDir(): File {
        val lib = store.libraryRoot()
        val exp = File(lib, "Exports")
        return if (exp.mkdirs() || exp.isDirectory) exp else lib
    }

    // --------------------------------------------------------------- status

    private fun updateStatus() {
        if (!prefs.showStatusBar && statusMessage == null) return
        val name = store.current?.name ?: "untitled"
        val dot = if (store.dirty) " •" else ""
        statusLeft.text = statusMessage ?: "☰  $name$dot"

        val words = wordCount()
        val modes = buildString {
            if (prefs.focusMode) append("focus ")
            if (prefs.typewriterMode) append("typewriter ")
            if (prefs.sourceMode) append("source ")
        }.trim()
        statusRight.text = buildString {
            if (modes.isNotEmpty()) append(modes).append("  ·  ")
            append("$words words  ·  ${readingTime(words)}")
            append("  ·  ${Math.round(Scale.ui * 100)}%")
        }
    }

    /**
     * The status bar's word count, taken from the last count rather than made
     * fresh. Counting walks the whole document and the bar is refreshed on
     * every keystroke, so on a manuscript this used to be a full pass over the
     * book per character typed. The number can lag a moment; the sentence being
     * typed cannot.
     */
    private fun wordCount(): Int = cachedWords

    private fun recount() {
        cachedWords = DocStore.countWords(editor.text)
    }

    /**
     * Queued when the text changes, and only then — a timer that re-armed
     * itself would walk the document every few hundred milliseconds for as long
     * as the app was open, whether anything had been written or not.
     */
    private val recountSoon = Runnable {
        recount()
        updateStatus()
    }

    private fun readingTime(words: Int): String {
        val mins = Math.max(1, Math.round(words / 220f))
        return "$mins min"
    }

    private val clearFlash = Runnable {
        statusMessage = null
        applyChrome()
        updateStatus()
    }

    /**
     * With the status bar switched off a message has nowhere to land, and "Save
     * failed" is the one that must never go unseen. The bar therefore comes back
     * for as long as the message lasts and then goes away again — a single
     * shared timer, so a second message cannot be cut short by the first one's.
     */
    private fun flash(msg: String) {
        statusMessage = msg
        applyChrome()
        updateStatus()
        ui.removeCallbacks(clearFlash)
        ui.postDelayed(clearFlash, 3200)
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} kB"
        else -> "${bytes / (1024 * 1024)} MB"
    }

    // ------------------------------------------------------------ e ink

    /**
     * Drives the panel to full black and back, which is the only reliable way to
     * make an E Ink controller do a full-screen update and clear the grey ghosts
     * that partial updates leave behind.
     */
    private fun flashRefresh() {
        editsSinceRefresh = 0
        val v = View(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(
            v, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        ui.postDelayed({
            v.setBackgroundColor(Color.WHITE)
            ui.postDelayed({ root.removeView(v) }, 70)
        }, 90)
    }

    // -------------------------------------------------------------- prompts

    private fun promptRename() {
        scrim.visibility = View.VISIBLE
        sheet.configure(
            "Rename", store.current?.name ?: "name.md", emptyList(),
            "Type the new name, then Enter"
        )
        sheet.onPick = null
        sheet.onFreeText = { name ->
            closeSheets()
            val f = store.rename(name)
            flash(if (f != null) "Renamed to ${f.name}" else "Rename failed")
            updateStatus()
        }
        sheet.show()
    }

    private fun promptDelete() {
        val name = store.current?.name ?: return
        scrim.visibility = View.VISIBLE
        sheet.configure(
            "Delete $name?", "type DELETE to confirm", emptyList(),
            "This cannot be undone"
        )
        sheet.onPick = null
        sheet.onFreeText = { answer ->
            closeSheets()
            if (answer.equals("DELETE", ignoreCase = true)) {
                val ok = store.delete()
                if (ok) { editor.setText(""); newDocument() }
                flash(if (ok) "Deleted $name" else "Delete failed")
            } else flash("Not deleted")
        }
        sheet.show()
    }

    /**
     * Follows a link. Three kinds, in the order a writer is likely to mean
     * them: a heading in this document, another document beside it, and only
     * then something the rest of the device should handle.
     */
    private fun openLink(raw: String) {
        // A target may carry a title — [a](x.md "note") — and may be bracketed.
        val trimmed = raw.trim()
        val target = if (trimmed.startsWith("<")) {
            val end = trimmed.indexOf('>')
            (if (end > 0) trimmed.substring(1, end) else trimmed.substring(1)).trim()
        } else {
            // A title is quoted — [x](url "note") — so only a quote ends the
            // target. Splitting at the first space instead would cut a heading
            // anchor like #Chapter one down to #Chapter.
            Regex("^(\\S+)\\s+[\"'(].*$").find(trimmed)?.groupValues?.get(1) ?: trimmed
        }
        if (target.isEmpty()) return

        // A heading or a filename with a space in it arrives percent-encoded,
        // and neither an anchor nor a path can be matched until it is decoded.
        // The address handed to the system further down keeps its encoding,
        // which is what a URL is supposed to carry.
        val decoded = ImageCache.percentDecode(target)

        if (decoded.startsWith("#")) {
            jumpToHeading(decoded.removePrefix("#"))
            return
        }

        val hasScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(target)
        if (!hasScheme) {
            val here = store.current?.parentFile ?: store.libraryRoot()
            val f = File(here, decoded)
            // Opening a file also makes it the document that autosave writes
            // back to, so a link is not allowed to walk out of the library with
            // ../.. and hand the editor something elsewhere on the card.
            if (f.isFile && store.isText(f) && withinLibrary(f, here)) {
                openWithSave(f)
                flash("Opened ${f.name}")
                return
            }
        }

        // Bare addresses are written without a scheme far more often than not.
        val uri = Uri.parse(if (hasScheme) target else "https://$target")
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { flash("Nothing on this device opens $uri") }
    }

    /**
     * True when [f] really sits inside the document's own folder or the library.
     * Resolved canonically, so `../` and a symlink are both answered by where
     * the path ends up rather than by how it was written.
     */
    private fun withinLibrary(f: File, here: File): Boolean = runCatching {
        val path = f.canonicalPath
        listOf(here, store.libraryRoot()).any { root ->
            val r = root.canonicalPath
            path == r || path.startsWith(r + File.separator)
        }
    }.getOrDefault(false)

    /** GitHub-style anchor: "my heading" matches "## My Heading". */
    private fun jumpToHeading(anchor: String) {
        val want = anchor.trim().lowercase().replace(' ', '-')
        val heading = MarkdownStyler.outline(editor.text).firstOrNull {
            it.title.lowercase().replace(Regex("[^a-z0-9 -]"), "").trim()
                .replace(' ', '-') == want
        }
        if (heading == null) {
            flash("No heading called “$anchor”")
            return
        }
        editor.setSelection(heading.offset.coerceIn(0, editor.text.length))
        editor.post { editor.centreCaret() }
    }

    private fun openWithSave(f: File) {
        if (store.dirty) saveQuietly()
        loadInto(f)
    }

    companion object {
        private const val RECOVER_RESTORE = "recover:restore"
        private const val RECOVER_DISCARD = "recover:discard"

        /**
         * What an explicit save waits for. It has to be the last word, so it
         * waits as long as the card needs.
         */
        private const val FULL_DRAIN_MS = 5_000L

        /** What a pause waits for; see onPause for why it is not the above. */
        private const val PAUSE_DRAIN_MS = 400L

        /**
         * How long the word count may lag the text. Long enough that a burst of
         * typing costs one pass over the document rather than one per key,
         * short enough to read as immediate.
         */
        private const val RECOUNT_MS = 150L

        private val WELCOME = """
            # Welcome to Slate

            A distraction-free Markdown writer built for E Ink and a Bluetooth
            keyboard. Markdown formats itself as you type; the syntax markers
            disappear once the caret leaves the line, and come back in grey when
            you move onto it again.

            Press **Ctrl P** for the command palette. Every command is in there
            with its shortcut, so nothing below needs memorising.

            ## If anything looks too small

            **Ctrl =** and **Ctrl -** resize the whole interface, text and panels
            together, and it is remembered. Sizes are measured in real
            millimetres against the panel, not in Android's `dp`, which is why
            this app does not come out half-size the way most sideloaded apps do.

            ## Writing

            - **Ctrl B** bold, **Ctrl I** italic, **Ctrl E** code
            - **Ctrl 1** to **Ctrl 6** headings, **Ctrl 0** back to paragraph
            - **Ctrl Shift L** bullets, **Ctrl Shift N** numbers, **Ctrl Shift T** tasks
            - Enter continues a list; Enter on an empty item ends it
            - Tab and Shift Tab indent and outdent

            ## Modes

            - **F8** focus mode, which dims every paragraph but this one
            - **F9** typewriter mode, which keeps the caret at the middle of the screen
            - **Ctrl /** shows the raw Markdown when you want to see it
            - **Ctrl R** flashes the panel to clear E Ink ghosting

            ## Files

            Documents are plain `.md` files under this folder, so the Supernote
            file browser and a USB cable can both reach them. **Ctrl O** opens the
            file list — typing a name that does not exist creates it.

            ---

            > Delete all of this and start writing.
        """.trimIndent()
    }
}
