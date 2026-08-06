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
        assertFalse(
            "an unwritable folder would fail every later save",
            root.absolutePath == readOnly.absolutePath
        )
        assertTrue("the fallback must be writable", root.canWrite())
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
        if (made != null) assertTrue(made.exists())

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
}
