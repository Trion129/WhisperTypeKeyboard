package me.trion.whispertype.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelDescriptorTest {
    @Test
    fun `segment limit defaults by layout and keeps the Whisper cap`() {
        assertEquals(28_000L, defaultMaxSegmentMs(ModelLayout.Whisper("e", "d", "t")))
        assertEquals(45_000L, defaultMaxSegmentMs(ModelLayout.NemoCtc("m", "t")))
        assertEquals(30_000L, defaultMaxSegmentMs(ModelLayout.Canary("e", "d", "t")))
        assertEquals(39_000L, validateMaxSegmentMs(39_000L, ModelLayout.Canary("e", "d", "t")))
        assertEquals(60_000L, validateMaxSegmentMs(60_000L, ModelLayout.NemoCtc("m", "t")))
        assertThrows(IllegalArgumentException::class.java) {
            validateMaxSegmentMs(28_001L, ModelLayout.Whisper("e", "d", "t"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateMaxSegmentMs(40_000L, ModelLayout.Canary("e", "d", "t"))
        }
    }

    @Test
    fun `canary transcribes in the spoken language instead of translating from English`() {
        val config = SherpaCanaryEngine.canaryConfig("e", "d", "es")
        assertEquals("es", config.srcLang)
        assertEquals("es", config.tgtLang)
        assertEquals(true, config.usePnc)
    }

    @Test
    fun `canary pads clips under one second symmetrically`() {
        val padded = SherpaCanaryEngine.padToMinimum(FloatArray(4_000) { 1f }, 16_000)
        assertEquals(16_000, padded.size)
        assertEquals(0f, padded[5_999])
        assertEquals(1f, padded[6_000])
        assertEquals(1f, padded[9_999])
        assertEquals(0f, padded[10_000])
        val long = FloatArray(16_000)
        assertEquals(true, SherpaCanaryEngine.padToMinimum(long, 16_000) === long)
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
    fun `shared transcript formatting trims and keeps the model's letter case`() {
        assertEquals("hello", formatTranscript("  hello  "))
        assertEquals("école", formatTranscript("école"))
        assertEquals("Hello", formatTranscript(" Hello"))
        assertEquals("नमस्ते", formatTranscript(" नमस्ते "))
        assertEquals("", formatTranscript("  "))
    }
}
