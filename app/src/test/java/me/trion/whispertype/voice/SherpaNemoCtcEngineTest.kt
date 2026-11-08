package me.trion.whispertype.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SherpaNemoCtcEngineTest {
    @Test
    fun `lead in adds 200 ms of silence before the audio`() {
        val samples = FloatArray(1_000) { 0.5f }
        val out = SherpaNemoCtcEngine.withLeadIn(samples, 16_000)
        assertEquals(1_000 + 3_200, out.size)
        assertEquals(0f, out[3_199])
        assertEquals(0.5f, out[3_200])
        assertEquals(0, SherpaNemoCtcEngine.withLeadIn(FloatArray(0), 16_000).size)
    }
}
