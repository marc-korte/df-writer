package com.dfwriter.slate

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The file layer is where a bug costs the user words rather than pixels, so it
 * is tested against a real filesystem rather than mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DocStoreTest {

    private lateinit var ctx: Context
    private lateinit var prefs: Prefs
    private lateinit var store: DocStore
    private lateinit var lib: File

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        prefs = Prefs(ctx)
        lib = File(ctx.cacheDir, "library-${System.nanoTime()}")
        lib.mkdirs()
        prefs.libraryPath = lib.absolutePath
        prefs.lastFile = ""
        store = DocStore(ctx, prefs)
        store.clearScratch()
    }

    @After
    fun tearDown() {
        lib.deleteRecursively()
        store.clearScratch()
    }

    // ------------------------------------------------------------- library

    @Test
    fun `the configured library folder is used when it is writable`() {
        assertEquals(lib.absolutePath, store.libraryRoot().absolutePath)
    }

    @Test
    fun `a library folder that cannot be written is not used`() {
        val readOnly = File(ctx.cacheDir, "readonly-${System.nanoTime()}")
        readOnly.mkdirs()
        readOnly.setWritable(false, false)
        prefs.libraryPath = readOnly.absolutePath

        val root = DocStore(ctx, prefs).libraryRoot()
        // Running as root — which a CI container usually is — the permission bit
        // is ignored and the folder really is still writable, so there is
        // nothing to reject. Whichever way the platform went, what comes back
        // has to be a folder that can actually take a save.
        if (!readOnly.canWrite()) {
            assertFalse(
                "an unwritable folder would fail every later save",
                root.absolutePath == readOnly.absolutePath
            )
        }
        assertTrue("the chosen folder must be writable", root.canWrite())
        readOnly.setWritable(true, false)
        readOnly.deleteRecursively()
    }

    // ------------------------------------------------------------- writing

    @Test
    fun `save writes the text and leaves no temporary file behind`() {
        val f = store.createAndOpen(lib, "notes")
        assertNotNull(f)
        store.save("hello there")

        assertEquals("hello there", f!!.readText())
        val leftovers = lib.listFiles()!!.filter { it.name.startsWith(".") }
        assertTrue("temp files must not survive a save: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `save clears the dirty flag and save with no document returns null`() {
        val f = store.createAndOpen(lib, "notes")!!
        store.dirty = true
        assertNotNull(store.save("text"))
        assertFalse(store.dirty)
        assertEquals("text", f.readText())

        val fresh = DocStore(ctx, prefs)
        assertNull("nothing open means nothing to save", fresh.save("orphan"))
    }

    @Test
    fun `a failed save leaves the previous contents intact`() {
        val f = store.createAndOpen(lib, "precious")!!
        store.save("the original words")

        // A directory in place of the target makes every write fail.
        f.delete()
        f.mkdirs()

        assertNull("save must report failure rather than throw", store.save("replacement"))
        assertTrue("the original must not have been destroyed", f.isDirectory)

        // The temp copy is deliberately left behind here: the swap never
        // happened, so it holds the only complete copy of the new text. What
        // must not happen is a pile of half-written spares beside it, or a
        // temp file holding something other than what was being saved.
        val leftovers = lib.listFiles()!!.filter { it.name.startsWith(".") }
        assertEquals(
            "only the temp copy of the new text may survive: $leftovers",
            listOf(".precious.md.tmp"),
            leftovers.map { it.name }
        )
        assertEquals("the rescued text must be whole", "replacement", leftovers[0].readText())
        f.deleteRecursively()
    }

    @Test
    fun `creating a document twice does not overwrite the first`() {
        val a = store.createAndOpen(lib, "same name")!!
        a.writeText("first")
        val b = store.createAndOpen(lib, "same name")!!

        assertFalse("the second file must be distinct", a.absolutePath == b.absolutePath)
        assertEquals("first", a.readText())
    }

    @Test
    fun `creating in an unwritable folder reports failure instead of throwing`() {
        val blocked = File(ctx.cacheDir, "blocked-${System.nanoTime()}")
        blocked.mkdirs()
        blocked.setWritable(false, false)

        // Either it fails, or the platform ignored the permission; both are safe,
        // what matters is that it never throws on the autosave path.
        val made = store.createNamed(blocked, "nope")
        if (blocked.canWrite()) {
            // Root ignores the permission bit, so the folder is writable after
            // all and a real file has to come back.
            assertNotNull("a writable folder must yield a document", made)
            assertEquals("nope.md", made!!.name)
            assertTrue("the document must exist on disk", made.isFile)
        } else {
            assertNull("an unwritable folder must report failure, not a file", made)
            assertFalse("nothing may be left behind", File(blocked, "nope.md").exists())
        }

        blocked.setWritable(true, false)
        blocked.deleteRecursively()
    }

    // -------------------------------------------------------------- naming

    @Test
    fun `rename moves the content and refuses to clobber an existing file`() {
        val f = store.createAndOpen(lib, "before")!!
        store.save("body text")

        val renamed = store.rename("after.md")
        assertNotNull(renamed)
        assertEquals("body text", renamed!!.readText())
        assertFalse("the old name must be gone", f.exists())

        File(lib, "taken.md").writeText("someone else")
        assertNull("renaming onto an existing file must be refused", store.rename("taken.md"))
        assertEquals("someone else", File(lib, "taken.md").readText())
        assertTrue("the document must still be there", renamed.exists())
    }

    @Test
    fun `a name that only looks like it has an extension still gets one`() {
        // "contains a dot" is not the test: a document called "Notes v1.2" would
        // keep that name, and then drop out of the browser it was made from.
        assertEquals("Notes v1.2.md", DocStore.ensureExt("Notes v1.2"))
        assertEquals("Notes v1.2.md", DocStore.ensureExt("  Notes v1.2  "))
        assertEquals("read.markdown", DocStore.ensureExt("read.markdown"))
        assertEquals("shout.TXT", DocStore.ensureExt("shout.TXT"))

        // Whatever name it settles on, the listing has to be willing to show it.
        for (typed in listOf("Notes v1.2", "plain", "read.markdown", "shout.TXT", "notes.text")) {
            val named = DocStore.ensureExt(typed)
            assertTrue(
                "\"$typed\" became \"$named\", which the browser would hide",
                store.isText(File(lib, named))
            )
        }
    }

    @Test
    fun `delete removes the file and forgets it`() {
        val f = store.createAndOpen(lib, "doomed")!!
        assertTrue(store.delete())
        assertFalse(f.exists())
        assertNull(store.current)
        assertNull("a deleted document must not be reopened next launch",
            prefs.lastFile.ifEmpty { null })
    }

    // ------------------------------------------------------------ browsing

    @Test
    fun `listing shows folders first and only text files`() {
        File(lib, "zeta").mkdirs()
        File(lib, "notes.md").writeText("x")
        File(lib, "plain.txt").writeText("x")
        File(lib, "photo.png").writeText("x")
        File(lib, ".hidden").mkdirs()

        val names = store.list(lib).map { it.file.name }
        assertEquals("zeta", names.first())
        assertTrue(names.contains("notes.md"))
        assertTrue(names.contains("plain.txt"))
        assertFalse("binary files are not documents", names.contains("photo.png"))
        assertFalse("dot folders are noise", names.contains(".hidden"))
    }

    @Test
    fun `open reads the text and remembers the document`() {
        val f = File(lib, "read-me.md")
        f.writeText("on disk")
        assertEquals("on disk", store.open(f))
        assertEquals(f.absolutePath, store.current?.absolutePath)
        assertEquals(f.absolutePath, prefs.lastFile)
        assertFalse(store.dirty)
    }

    // ------------------------------------------------------------- races

    @Test
    fun `a save queued before a rename does not put the old name back`() {
        val f = store.createAndOpen(lib, "before")!!
        store.save("original")
        val gen = store.generation()

        val renamed = store.rename("after.md")!!
        // The write was snapshotted against the old name and only reaches the
        // card now, which is exactly the order an autosave can arrive in.
        assertFalse("a stale write must be refused", store.writeThrough(f, "late text", gen))
        assertFalse("the old name must not come back", f.exists())
        assertEquals("original", renamed.readText())
    }

    @Test
    fun `a save queued before a delete does not resurrect the document`() {
        val f = store.createAndOpen(lib, "doomed")!!
        store.save("text")
        val gen = store.generation()

        assertTrue(store.delete())
        assertFalse(store.writeThrough(f, "late text", gen))
        assertFalse("a deleted document must stay deleted", f.exists())
    }

    @Test
    fun `a save queued before the rename cannot reclaim the shadow copy`() {
        val f = store.createAndOpen(lib, "owner")!!
        store.save("text")
        val gen = store.generation()
        store.rename("renamed.md")
        val ownerAfterRename = store.scratchOwner()

        store.writeThrough(f, "late text", gen)
        assertEquals(
            "the shadow copy must still belong to the surviving name",
            ownerAfterRename, store.scratchOwner()
        )
    }

    @Test
    fun `a save carrying the current generation still lands`() {
        val f = store.createAndOpen(lib, "live")!!
        assertTrue(store.writeThrough(f, "current text", store.generation()))
        assertEquals("current text", f.readText())
    }

    @Test
    fun `a save with no generation given is never refused`() {
        // The main-thread path passes none; it is writing what it just read.
        val f = store.createAndOpen(lib, "plain")!!
        store.rename("moved.md")
        assertTrue(store.writeThrough(store.current!!, "text"))
    }

    @Test
    fun `a refusal and a failure are told apart`() {
        val f = store.createAndOpen(lib, "live")!!
        val gen = store.generation()
        assertEquals(
            DocStore.WriteResult.WROTE,
            store.writeThroughResult(f, "text", gen)
        )

        store.rename("moved.md")
        assertEquals(
            "a write for a renamed document is stale, not failed",
            DocStore.WriteResult.STALE,
            store.writeThroughResult(f, "late", gen)
        )

        // A path that cannot be written is a real failure, not a stale write.
        val blocked = File(lib, "nodir/sub/doc.md")
        File(lib, "nodir").writeText("this is a file, not a folder")
        assertEquals(
            DocStore.WriteResult.FAILED,
            store.writeThroughResult(blocked, "text", store.generation())
        )
    }

    // --------------------------------------------------- swap-in-place safety

    @Test
    fun `a save never leaves the document half written`() {
        // Whatever the card does with the rename, what ends up at the path is
        // one complete version of the text — never an empty or partial file.
        val f = store.createAndOpen(lib, "precious")!!
        store.save("the original words")
        // An occupied backup name is the case where the move aside cannot work.
        File(lib, ".${f.name}.bak").mkdirs()

        store.writeThrough(f, "the replacement words")

        assertTrue("the document must still exist", f.isFile)
        val text = f.readText()
        assertTrue(
            "expected one whole version, got: $text",
            text == "the original words" || text == "the replacement words"
        )
    }

    // ------------------------------------------------------------ recovery

    @Test
    fun `nothing is offered when the shadow copy matches the file`() {
        store.createAndOpen(lib, "doc")
        store.save("same text")
        store.writeScratch("same text")

        assertNull(
            "a clean exit must not produce a recovery prompt",
            store.recoverableText("same text")
        )
    }

    @Test
    fun `text that never reached disk is offered back`() {
        store.createAndOpen(lib, "doc")
        store.save("saved version")
        store.writeScratch("saved version plus unsaved words")

        assertEquals(
            "saved version plus unsaved words",
            store.recoverableText("saved version")
        )
    }

    @Test
    fun `a shadow copy belonging to another document is not offered`() {
        store.createAndOpen(lib, "first")
        store.writeScratch("text from the first document")

        // Open a different document; the stale scratch must not follow.
        val other = File(lib, "second.md")
        other.writeText("second document")
        store.open(other)

        assertNull(store.recoverableText("second document"))
    }

    @Test
    fun `discarding the recovered text stops it being offered again`() {
        store.createAndOpen(lib, "doc")
        store.writeScratch("unsaved")
        assertNotNull(store.recoverableText(""))

        store.clearScratch()
        assertNull(store.recoverableText(""))
    }

    @Test
    fun `an empty shadow copy is never offered`() {
        store.createAndOpen(lib, "doc")
        store.writeScratch("")
        assertNull(store.recoverableText("anything"))
    }

    @Test
    fun `a save leaves nothing to recover`() {
        // Regression: the shadow copy used to be left older than the file, so
        // every launch after a normal save offered the previous text back.
        store.createAndOpen(lib, "doc")
        store.save("first pass")
        assertNull("a saved document has nothing outstanding", store.recoverableText("first pass"))

        store.save("second pass")
        assertEquals("the shadow copy must track the file", "second pass", store.readScratch())
        assertNull(store.recoverableText("second pass"))
    }

    @Test
    fun `text kept when there was nowhere to save it is offered to whatever opens next`() {
        // When the card cannot be written the text is parked with no document
        // attached. It belongs to nothing, so nothing else would ever claim it,
        // and dropping it would be the one loss this whole mechanism exists to
        // prevent.
        val homeless = DocStore(ctx, prefs)
        homeless.writeScratch("words with nowhere to go")
        assertNull("nothing open means no owner", homeless.scratchOwner())

        store.createAndOpen(lib, "rescue")
        assertEquals("words with nowhere to go", store.recoverableText(""))
    }

    @Test
    fun `renaming carries the shadow copy with it`() {
        store.createAndOpen(lib, "before")
        store.save("body text")
        store.writeScratch("body text plus unsaved words")

        val renamed = store.rename("after.md")!!
        assertEquals(
            "the draft is matched by path, so it must follow the rename",
            renamed.absolutePath, store.scratchOwner()
        )
        assertEquals("body text plus unsaved words", store.recoverableText("body text"))
    }

    @Test
    fun `deleting a document takes its draft with it`() {
        store.createAndOpen(lib, "doomed")
        store.writeScratch("a draft of the doomed document")
        assertTrue(store.delete())

        assertNull("the draft must not outlive the document", store.readScratch())
        assertNull(store.scratchOwner())

        // A document made in the same minute can be handed the same name, and
        // must not inherit the dead one's words.
        store.createAndOpen(lib, "doomed")
        assertNull(store.recoverableText(""))
    }
}
