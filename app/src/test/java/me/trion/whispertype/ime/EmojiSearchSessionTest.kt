package me.trion.whispertype.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiSearchSessionTest {
    @Test
    fun `letters digits and spaces build a local query`() {
        val session = EmojiSearchSession()
        session.append("red")
        session.append(" ")
        session.append("heart")
        session.append("2")

        assertEquals("red heart2", session.query)
    }

    @Test
    fun `backspace and clear only change query state`() {
        val session = EmojiSearchSession("cry")

        assertEquals("cr", session.backspace())
        assertEquals("", session.clear())
    }

    @Test
    fun `backspace removes one astral code point`() {
        val session = EmojiSearchSession("😀")

        assertEquals("", session.backspace())
    }

    @Test
    fun `unsupported query characters are ignored`() {
        val session = EmojiSearchSession("ha")

        assertEquals("ha", session.append("😂"))
        assertEquals("ha", session.append("!"))
    }
}
