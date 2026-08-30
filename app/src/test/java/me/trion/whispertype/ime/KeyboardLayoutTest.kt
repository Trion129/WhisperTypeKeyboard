package me.trion.whispertype.ime

import org.junit.Assert.*
import org.junit.Test

class KeyboardLayoutTest {

    @Test
    fun `letters starts with qwerty not a digit row`() {
        val letters = KeyboardLayout.letters
        assertEquals(4, letters.size)
        assertEquals("q", letters[0].first().label)
        assertTrue(letters.none { row -> row.any { it.label == "1" } })
    }

    @Test
    fun `every mode bottom row has mic space enter and no globe`() {
        val modes = listOf(
            KeyboardLayout.letters,
            KeyboardLayout.numbers,
            KeyboardLayout.symbols,
            KeyboardLayout.emoji
        )
        modes.forEach { rows ->
            val bottom = rows.last()
            assertTrue(bottom.none { it.label == "🌐" })
            assertTrue(bottom.any { it.type == KeyType.MIC })
            assertTrue(bottom.any { it.type == KeyType.SPACE })
            assertTrue(bottom.any { it.type == KeyType.ENTER })
        }
    }

    @Test
    fun `letter e carries accent popups from the catalog`() {
        val e = KeyboardLayout.letters[0].first { it.label == "e" }
        assertEquals(KeyPopupCatalog.popupsFor("e"), e.popupLabels)
    }

    @Test
    fun `emoji search has popup free qwerty and local actions`() {
        val rows = KeyboardLayout.emojiSearch

        assertEquals(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"), rows[0].map { it.label })
        assertEquals(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"), rows[1].map { it.label })
        assertEquals(listOf("z", "x", "c", "v", "b", "n", "m"), rows[2].filter { it.type == KeyType.CHAR }.map { it.label })
        assertTrue(rows[2].any { it.type == KeyType.BACKSPACE })

        val bottom = rows.last()
        assertTrue(bottom.any { it.type == KeyType.SPACE })
        assertTrue(bottom.any { it.type == KeyType.MODE_EMOJI })
        assertTrue(bottom.any { it.type == KeyType.MODE_ABC })

        val queryKeys = rows.flatten().filter { it.type == KeyType.CHAR || it.type == KeyType.SPACE }
        assertTrue(queryKeys.isNotEmpty())
        assertTrue(queryKeys.all { it.popupLabels.isEmpty() })
    }

    @Test
    fun `numbers layer has the digit row`() {
        assertEquals(4, KeyboardLayout.numbers.size)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), KeyboardLayout.numbers[0].map { it.label })
        assertEquals(4, KeyboardLayout.symbols.size)
        assertEquals(4, KeyboardLayout.emoji.size)
    }
}
