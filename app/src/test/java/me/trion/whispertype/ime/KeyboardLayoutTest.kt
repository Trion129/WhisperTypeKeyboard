package me.trion.whispertype.ime

import org.junit.Assert.*
import org.junit.Test

class KeyboardLayoutTest {

    @Test
    fun `letters has a digit row then qwerty`() {
        val letters = KeyboardLayout.letters
        assertEquals(5, letters.size)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), letters[0].map { it.label })
        assertEquals("q", letters[1].first().label)
    }

    @Test
    fun `every mode bottom row has globe mic space enter`() {
        val modes = listOf(
            KeyboardLayout.letters,
            KeyboardLayout.numbers,
            KeyboardLayout.symbols,
            KeyboardLayout.emoji
        )
        modes.forEach { rows ->
            val bottom = rows.last()
            assertTrue(bottom.any { it.type == KeyType.GLOBE })
            assertTrue(bottom.any { it.type == KeyType.MIC })
            assertTrue(bottom.any { it.type == KeyType.SPACE })
            assertTrue(bottom.any { it.type == KeyType.ENTER })
        }
    }

    @Test
    fun `letter e carries accent popups from the catalog`() {
        val e = KeyboardLayout.letters[1].first { it.label == "e" }
        assertEquals(KeyPopupCatalog.popupsFor("e"), e.popupLabels)
    }

    @Test
    fun `non-letter modes do not include a fifth letter row`() {
        assertEquals(4, KeyboardLayout.numbers.size)
        assertEquals(4, KeyboardLayout.symbols.size)
        assertEquals(4, KeyboardLayout.emoji.size)
    }
}
