package com.dfwriter.slate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

/**
 * How documents get in and out of the app: the intents the file browser sends,
 * and the lifecycle around them. These paths are only ever exercised from
 * outside the process on the device, so they are exercised from outside the
 * activity here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MainFlowTest {

    private lateinit var ctx: Context
    private lateinit var prefs: Prefs
    private lateinit var lib: File

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        prefs = Prefs(ctx)
        lib = File(ctx.cacheDir, "lib-${System.nanoTime()}").apply { mkdirs() }
        prefs.libraryPath = lib.absolutePath
        prefs.lastFile = ""
        File(ctx.filesDir, "scratch.md").delete()
        File(ctx.filesDir, "scratch.path").delete()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A content URI the resolver will hand [body] back for, as a browser would. */
    private fun contentUri(name: String, body: String): Uri {
        val uri = Uri.parse("content://com.example.documents/$name")
        shadowOf(ctx.contentResolver).registerInputStreamSupplier(uri) {
            body.byteInputStream()
        }
        return uri
    }

    private fun startWith(uri: Uri?): ActivityController<MainActivity> {
        val c = if (uri == null) {
            Robolectric.buildActivity(MainActivity::class.java)
        } else {
            Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_VIEW, uri))
        }
        c.setup()
        idle()
        return c
    }

    private fun find(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) find(root.getChildAt(i), match)?.let { return it }
        }
        return null
    }

    private fun editorOf(a: MainActivity) =
        find(a.window.decorView) { it is MarkdownEditor } as MarkdownEditor

    private fun bodyOf(c: ActivityController<MainActivity>) = editorOf(c.get()).text.toString()

    // --------------------------------------------------------------- intents

    @Test
    fun `a file intent opens that document`() {
        val f = File(lib, "handed-over.md").apply { writeText("text from the file browser") }
        val c = startWith(Uri.fromFile(f))
        assertEquals("text from the file browser", bodyOf(c))
        c.pause().stop().destroy()
    }

    @Test
    fun `a content uri is copied into the library and edited there`() {
        // A content URI cannot be written back in place, so the text is copied
        // into the library rather than edited into somewhere that will not keep
        // it.
        val c = startWith(contentUri("notes.md", "text handed over by the browser"))
        val copy = File(lib, "notes.md")
        assertTrue("the import should have landed in the library", copy.isFile)
        assertEquals("text handed over by the browser", copy.readText())
        assertEquals("and be what is on screen", "text handed over by the browser", bodyOf(c))
        c.pause().stop().destroy()
    }

    @Test
    fun `handing over the same name twice does not overwrite the first copy`() {
        val first = startWith(contentUri("notes.md", "the first version"))
        first.pause().stop().destroy()

        val second = startWith(contentUri("notes.md", "a different document, same name"))
        assertEquals(
            "the copy already edited here must survive",
            "the first version", File(lib, "notes.md").readText()
        )
        assertEquals(
            "a name in use takes a suffix",
            "a different document, same name", File(lib, "notes-2.md").readText()
        )
        assertEquals("a different document, same name", bodyOf(second))
        second.pause().stop().destroy()

        // Something already imported is reopened rather than piling up another
        // copy on every visit from the file browser.
        val third = startWith(contentUri("notes.md", "the first version"))
        assertFalse("a third copy of the same text", File(lib, "notes-3.md").exists())
        assertEquals("the first version", bodyOf(third))
        third.pause().stop().destroy()
    }

    @Test
    fun `a new intent opens its document and is remembered`() {
        val c = startWith(null)
        val other = File(lib, "second.md").apply { writeText("the second document") }
        val uri = Uri.fromFile(other)

        c.newIntent(Intent(Intent.ACTION_VIEW, uri))
        idle()

        assertEquals("the second document", bodyOf(c))
        // This activity is singleTask, so without setIntent it keeps its very
        // first intent for good: a relaunch after the process died would reopen
        // whatever it was started with rather than the document last in use.
        assertEquals(
            "the new intent should have replaced the old one",
            uri, c.get().intent.data
        )
        c.pause().stop().destroy()
    }
}
