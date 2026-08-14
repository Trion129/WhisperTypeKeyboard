package me.trion.whispertype.ime

import org.junit.Assert.*
import org.junit.Test

class TypingRulesTest {

    @Test
    fun `auto period after letter in normal field`() {
        assertTrue(TypingRules.shouldAutoPeriod("hello", FieldVariation.NORMAL))
        assertTrue(TypingRules.shouldAutoPeriod("ok2", FieldVariation.NORMAL))
    }

    @Test
    fun `auto period off for empty or non letter`() {
        assertFalse(TypingRules.shouldAutoPeriod("", FieldVariation.NORMAL))
        assertFalse(TypingRules.shouldAutoPeriod("hello ", FieldVariation.NORMAL))
        assertFalse(TypingRules.shouldAutoPeriod("hello.", FieldVariation.NORMAL))
    }

    @Test
    fun `auto period off in uri email password number`() {
        assertFalse(TypingRules.shouldAutoPeriod("hello", FieldVariation.URI))
        assertFalse(TypingRules.shouldAutoPeriod("hello", FieldVariation.EMAIL))
        assertFalse(TypingRules.shouldAutoPeriod("hello", FieldVariation.PASSWORD))
        assertFalse(TypingRules.shouldAutoPeriod("hello", FieldVariation.NUMBER))
    }

    @Test
    fun `sentence cap at start or after terminator`() {
        assertTrue(TypingRules.shouldSentenceCap("", FieldVariation.NORMAL))
        assertTrue(TypingRules.shouldSentenceCap("   ", FieldVariation.NORMAL))
        assertTrue(TypingRules.shouldSentenceCap("Hi.", FieldVariation.NORMAL))
        assertTrue(TypingRules.shouldSentenceCap("Hi. ", FieldVariation.NORMAL))
        assertTrue(TypingRules.shouldSentenceCap("Go?", FieldVariation.NORMAL))
        assertTrue(TypingRules.shouldSentenceCap("Wow!", FieldVariation.NORMAL))
        assertFalse(TypingRules.shouldSentenceCap("Hi", FieldVariation.NORMAL))
        assertFalse(TypingRules.shouldSentenceCap("Hi.", FieldVariation.PASSWORD))
    }

    @Test
    fun `previous word length includes trailing whitespace`() {
        assertEquals(5, TypingRules.previousWordLength("hello world"))
        assertEquals(6, TypingRules.previousWordLength("hello "))
        assertEquals(5, TypingRules.previousWordLength("hello"))
        assertEquals(0, TypingRules.previousWordLength(""))
        assertEquals(1, TypingRules.previousWordLength(" "))
    }
}
