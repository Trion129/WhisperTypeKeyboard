package me.trion.whispertype.ime

import org.junit.Assert.*
import org.junit.Test

class KeyPopupCatalogTest {

    @Test
    fun `letter a has the spec accents`() {
        assertEquals(
            listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"),
            KeyPopupCatalog.popupsFor("a")
        )
    }

    @Test
    fun `uppercase a uppercases accents except eszett`() {
        assertEquals(
            listOf("À", "Á", "Â", "Ä", "Æ", "Ã", "Å", "Ā"),
            KeyPopupCatalog.popupsFor("a", uppercase = true)
        )
        assertEquals(listOf("Ś", "Š", "ß"), KeyPopupCatalog.popupsFor("s", uppercase = true))
    }

    @Test
    fun `unknown key is empty`() {
        assertTrue(KeyPopupCatalog.popupsFor("q").isEmpty())
        assertTrue(KeyPopupCatalog.popupsFor("w", uppercase = true).isEmpty())
    }

    @Test
    fun `digit one has superscript and fractions`() {
        assertEquals(listOf("¹", "½", "⅓", "¼"), KeyPopupCatalog.popupsFor("1"))
    }

    @Test
    fun `currency and quotes match the spec`() {
        assertEquals(listOf("¢", "£", "€", "¥", "₹"), KeyPopupCatalog.popupsFor("$"))
        assertEquals(listOf("“", "”", "«", "»"), KeyPopupCatalog.popupsFor("\""))
        assertEquals(listOf("–", "—", "•"), KeyPopupCatalog.popupsFor("-"))
        assertEquals(listOf("\\", "|"), KeyPopupCatalog.popupsFor("/"))
    }

    @Test
    fun `space has the seven frequent emoji`() {
        assertEquals(
            listOf("😊", "😂", "👍", "❤️", "🔥", "🎉", "🙏"),
            KeyPopupCatalog.popupsFor(" ")
        )
        assertEquals(KeyPopupCatalog.popupsFor(" "), KeyPopupCatalog.popupsFor("space"))
    }
}
