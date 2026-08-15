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

    @Test
    fun `ascii letter deletes one unit`() {
        assertEquals(1, TypingRules.previousGraphemeLength("hello"))
        assertEquals(0, TypingRules.previousGraphemeLength(""))
    }

    @Test
    fun `emoji surrogate pair deletes as one grapheme`() {
        assertEquals(2, TypingRules.previousGraphemeLength("😀"))
        assertEquals(2, TypingRules.previousGraphemeLength("a😀"))
    }

    @Test
    fun `skin tone and zwj sequences delete as one grapheme`() {
        assertEquals("👋🏻".length, TypingRules.previousGraphemeLength("👋🏻"))
        assertEquals("👨‍👩‍👧".length, TypingRules.previousGraphemeLength("hi👨‍👩‍👧"))
    }

    @Test
    fun `flag and keycap sequences delete as one grapheme`() {
        assertEquals("🇺🇸".length, TypingRules.previousGraphemeLength("🇺🇸"))
        assertEquals("2️⃣".length, TypingRules.previousGraphemeLength("2️⃣"))
    }

    @Test
    fun `combining accent deletes with its base`() {
        assertEquals(2, TypingRules.previousGraphemeLength("e\u0301"))
        assertEquals(1, TypingRules.previousGraphemeLength("é"))
    }
}
