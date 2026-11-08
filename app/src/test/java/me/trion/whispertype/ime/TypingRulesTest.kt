package me.trion.whispertype.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
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
    fun `dictation mid sentence lowercases the first word`() {
        val n = FieldVariation.NORMAL
        assertEquals("then we left", TypingRules.matchDictationCase("Then we left", "I went home ", n))
        assertEquals("then we left", TypingRules.matchDictationCase("Then we left", "I went home,", n))
        assertEquals("über alles", TypingRules.matchDictationCase("Über alles", "Deutschland ", n))
    }

    @Test
    fun `dictation keeps capital at sentence start`() {
        val n = FieldVariation.NORMAL
        assertEquals("Then we left", TypingRules.matchDictationCase("Then we left", "", n))
        assertEquals("Then we left", TypingRules.matchDictationCase("Then we left", "Done. ", n))
        assertEquals("Then we left", TypingRules.matchDictationCase("Then we left", "Done?", n))
        assertEquals("Then we left", TypingRules.matchDictationCase("Then we left", "list\n", n))
        assertEquals("Then we left", TypingRules.matchDictationCase("Then we left", "list\n ", n))
    }

    @Test
    fun `dictation gets a capital at sentence start when the model gave none`() {
        val n = FieldVariation.NORMAL
        assertEquals("It was fun", TypingRules.matchDictationCase("it was fun", "I went home. ", n))
        assertEquals("École", TypingRules.matchDictationCase("école", "", n))
        assertEquals("A dog ran", TypingRules.matchDictationCase("a dog ran", "list\n", n))
        assertEquals("iPhone works", TypingRules.matchDictationCase("iPhone works", "", n))
        assertEquals("नमस्ते", TypingRules.matchDictationCase("नमस्ते", "", n))
        assertEquals("park today", TypingRules.matchDictationCase("park today", "I went to the ", n))
        assertEquals("in time", TypingRules.matchDictationCase("in time", "", FieldVariation.EMAIL))
    }

    @Test
    fun `dictation keeps deliberate capitals`() {
        val n = FieldVariation.NORMAL
        assertEquals("I think so", TypingRules.matchDictationCase("I think so", "and ", n))
        assertEquals("I'm here", TypingRules.matchDictationCase("I'm here", "and ", n))
        assertEquals("OK then", TypingRules.matchDictationCase("OK then", "and ", n))
        assertEquals("McDonald's", TypingRules.matchDictationCase("McDonald's", "at ", n))
        assertEquals("In time", TypingRules.matchDictationCase("In time", "x", FieldVariation.EMAIL))
        assertEquals("in time", TypingRules.matchDictationCase("In time", "just ", n))
        assertEquals("नमस्ते", TypingRules.matchDictationCase("नमस्ते", "और ", n))
    }

    @Test
    fun `enter sends by default and adds a new line when preferred`() {
        val send = EditorInfo.IME_ACTION_SEND
        val multi = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        val single = InputType.TYPE_CLASS_TEXT
        assertEquals(EnterAction.EDITOR_ACTION, TypingRules.enterAction(send, multi, false))
        assertEquals(EnterAction.NEW_LINE, TypingRules.enterAction(send, multi, true))
        // Single-line fields (search, URL) cannot hold a new line, so keep the action.
        assertEquals(EnterAction.EDITOR_ACTION, TypingRules.enterAction(send, single, true))
        assertEquals(
            EnterAction.KEY_EVENT,
            TypingRules.enterAction(send or EditorInfo.IME_FLAG_NO_ENTER_ACTION, multi, false),
        )
        assertEquals(EnterAction.KEY_EVENT, TypingRules.enterAction(EditorInfo.IME_ACTION_NONE, single, false))
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
