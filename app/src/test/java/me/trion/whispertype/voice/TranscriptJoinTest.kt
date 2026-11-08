package me.trion.whispertype.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptJoinTest {
    private fun join(vararg parts: TranscriptPart) = joinTranscriptSegments(parts.toList())

    @Test
    fun `pause cut in Latin adds a space`() {
        assertEquals(
            "I am speaking and I continue",
            join(TranscriptPart("I am speaking", false), TranscriptPart("and I continue", true)),
        )
    }

    @Test
    fun `hard cut in Latin adds no space`() {
        assertEquals(
            "I am speaking to you",
            join(TranscriptPart("I am speak", false), TranscriptPart("ing to you", false)),
        )
    }

    @Test
    fun `pause cut in Chinese adds no space`() {
        assertEquals("你好世界", join(TranscriptPart("你好", false), TranscriptPart("世界", true)))
    }

    @Test
    fun `hard cut in Chinese adds no space`() {
        assertEquals("你好世界", join(TranscriptPart("你好", false), TranscriptPart("世界", false)))
    }

    @Test
    fun `empty parts are skipped`() {
        assertEquals(
            "hello there",
            join(TranscriptPart("", true), TranscriptPart("hello", false), TranscriptPart("  ", true), TranscriptPart("there", true)),
        )
    }

    @Test
    fun `the first part has no leading space and keeps the model case`() {
        assertEquals("we went to the Store", join(TranscriptPart("we went", true), TranscriptPart("to the Store", true)))
    }

    @Test
    fun `plain string parts keep a space between them`() {
        assertEquals("we went to the Store", joinTranscriptSegments(listOf("we went", "to the Store")))
    }
}
