package me.trion.whispertype.ime

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClipboardStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(): ClipboardStore = ClipboardStore(File(tempFolder.root, "clipboard.json"))

    @Test
    fun `missing file is empty`() {
        assertTrue(store().snapshot().isEmpty())
    }

    @Test
    fun `capture prepends newest first`() {
        val s = store()
        s.capture("one", 1)
        s.capture("two", 2)
        assertEquals(listOf("two", "one"), s.snapshot().map { it.text })
    }

    @Test
    fun `blank and oversized and head duplicate are ignored`() {
        val s = store()
        assertEquals(CaptureResult.Ignored, s.capture("   ", 1))
        assertEquals(CaptureResult.Ignored, s.capture("x".repeat(8193), 1))
        assertTrue(s.capture("hello", 1) is CaptureResult.Added)
        assertEquals(CaptureResult.Ignored, s.capture("hello", 2))
        assertEquals(1, s.snapshot().size)
    }

    @Test
    fun `cap is twenty newest`() {
        val s = store()
        repeat(25) { s.capture("item-$it", it.toLong()) }
        val snap = s.snapshot()
        assertEquals(20, snap.size)
        assertEquals("item-24", snap.first().text)
        assertEquals("item-5", snap.last().text)
    }

    @Test
    fun `delete and clear persist`() {
        val s = store()
        s.capture("keep", 1)
        val gone = (s.capture("gone", 2) as CaptureResult.Added).items.first()
        s.delete(gone.id)
        s.delete("missing")
        assertEquals(listOf("keep"), s.snapshot().map { it.text })
        s.clear()
        assertTrue(s.snapshot().isEmpty())
        assertTrue(store().snapshot().isEmpty())
    }

    @Test
    fun `corrupt file yields empty snapshot`() {
        val file = File(tempFolder.root, "clipboard.json")
        file.writeText("not-json")
        assertTrue(ClipboardStore(file).snapshot().isEmpty())
    }

    @Test
    fun `newlines in text survive round trip`() {
        val s = store()
        s.capture("line1\nline2", 1)
        assertEquals("line1\nline2", s.snapshot().single().text)
    }
}
