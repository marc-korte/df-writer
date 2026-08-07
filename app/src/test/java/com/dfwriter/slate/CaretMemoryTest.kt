package com.dfwriter.slate

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The per-document caret memory. Switching between the parts of a book happens
 * constantly, so each document keeps its own place — and the store of places
 * must stay bounded however large the library grows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CaretMemoryTest {

    private fun prefs() = Prefs(RuntimeEnvironment.getApplication())

    @Test
    fun `each document keeps its own place`() {
        val p = prefs()
        p.rememberCaret("/a/one.md", 120)
        p.rememberCaret("/a/two.md", 7)
        p.rememberCaret("/a/one.md", 340)   // moved on since
        assertEquals(340, p.caretFor("/a/one.md"))
        assertEquals(7, p.caretFor("/a/two.md"))
        assertEquals("an unknown file starts at the top", 0, p.caretFor("/a/three.md"))
    }

    @Test
    fun `a path with spaces round-trips`() {
        val p = prefs()
        p.rememberCaret("/a/Welcome to Slate.md", 55)
        assertEquals(55, p.caretFor("/a/Welcome to Slate.md"))
    }

    @Test
    fun `the memory is bounded and forgets oldest first`() {
        val p = prefs()
        for (i in 1..250) p.rememberCaret("/a/doc-$i.md", i)
        assertEquals("the oldest entries must fall out", 0, p.caretFor("/a/doc-1.md"))
        assertEquals(250, p.caretFor("/a/doc-250.md"))
        assertEquals("the newest two hundred stay", 51, p.caretFor("/a/doc-51.md"))
    }
}
