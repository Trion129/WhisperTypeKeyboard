package me.trion.whispertype.ime

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandParserTest {
    @Test
    fun `each clear counts as one word`() {
        for (count in 1..4) {
            val phrase = "Whispy " + List(count) { "clear" }.joinToString(" ")
            val result = VoiceCommandParser.parse(phrase, "Whispy")!!
            assertEquals(VoiceCommandParser.Command.CLEAR_LAST_WORD, result.command)
            assertEquals(count, result.wordCount)
            assertEquals("", result.dictationBeforeCommand)
        }
    }

    @Test
    fun `recognizer punctuation and case do not change the count`() {
        val result = VoiceCommandParser.parse("  WHISPY, Clear, clear. CLEAR!  ", "Whispy")!!
        assertEquals(3, result.wordCount)
        assertEquals("", result.dictationBeforeCommand)
    }

    @Test
    fun `clear all and clear last word keep their meanings`() {
        val all = VoiceCommandParser.parse("Whispy clear all.", "Whispy")!!
        assertEquals(VoiceCommandParser.Command.CLEAR_ALL, all.command)
        val word = VoiceCommandParser.parse("Whispy clear last word.", "Whispy")!!
        assertEquals(VoiceCommandParser.Command.CLEAR_LAST_WORD, word.command)
        assertEquals(1, word.wordCount)
    }

    @Test
    fun `custom prefix supports repeated clear`() {
        val result = VoiceCommandParser.parse("Hey   keyboard clear clear", " Hey keyboard ")!!
        assertEquals(2, result.wordCount)
    }

    @Test
    fun `leading dictation is preserved`() {
        val result = VoiceCommandParser.parse(
            "I have a good day but I wish. Whispy clear clear.", "Whispy"
        )!!
        assertEquals("I have a good day but I wish.", result.dictationBeforeCommand)
        assertEquals(2, result.wordCount)
    }

    @Test
    fun `repetitions remove the requested words from the example sentence`() {
        val sentence = "i have a good day but i wish "
        val expected = listOf("i have a good day but i ", "i have a good day but ", "i have a good day ")
        for (count in 1..3) {
            val phrase = "Whispy " + List(count) { "clear" }.joinToString(" ")
            val result = VoiceCommandParser.parse(phrase, "Whispy")!!
            val length = TypingRules.previousWordsLength(sentence, result.wordCount)
            assertEquals(expected[count - 1], sentence.dropLast(length))
        }
    }

    @Test
    fun `ordinary or incomplete dictation is not a command`() {
        listOf(
            "clear clear",
            "NotWhispy clear clear",
            "Whispy clearer",
            "Whispy clear clear then keep writing",
            "Whispy clear clear all",
            "Whispy clear all clear",
        ).forEach { assertNull(it, VoiceCommandParser.parse(it, "Whispy")) }
        assertNull(VoiceCommandParser.parse("Whispy clear clear", "  "))
    }
}
