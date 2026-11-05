package me.trion.whispertype.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelDescriptorTest {
    @Test
    fun `segment limit defaults by layout and keeps the Whisper cap`() {
        assertEquals(28_000L, defaultMaxSegmentMs(ModelLayout.Whisper("e", "d", "t")))
        assertEquals(45_000L, defaultMaxSegmentMs(ModelLayout.NemoCtc("m", "t")))
        assertEquals(45_000L, defaultMaxSegmentMs(ModelLayout.Canary("e", "d", "t")))
        assertEquals(45_000L, validateMaxSegmentMs(45_000L, ModelLayout.Canary("e", "d", "t")))
        assertEquals(60_000L, validateMaxSegmentMs(60_000L, ModelLayout.NemoCtc("m", "t")))
        assertThrows(IllegalArgumentException::class.java) {
            validateMaxSegmentMs(28_001L, ModelLayout.Whisper("e", "d", "t"))
        }
    }

    @Test
    fun `language policies force, filter, and autodetect independently`() {
        val selectable = LanguagePolicy.Selectable(listOf("", "en", "hi"), defaultCode = "")
        assertEquals("hi", selectable.effectiveLanguage("hi"))
        assertEquals("", selectable.effectiveLanguage("unsupported"))
        assertEquals("", selectable.effectiveLanguage(""))
        assertEquals("hi", LanguagePolicy.Fixed("hi", "Hindi").effectiveLanguage("en"))
        assertEquals("", LanguagePolicy.Automatic("Model detected").effectiveLanguage("en"))
    }


    @Test
    fun `shared transcript formatting trims and capitalizes without rewriting other scripts`() {
        assertEquals("Hello", formatTranscript("  hello  "))
        assertEquals("École", formatTranscript("école"))
        assertEquals("नमस्ते", formatTranscript(" नमस्ते "))
        assertEquals("", formatTranscript("  "))
    }
}
