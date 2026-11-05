package me.trion.whispertype.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingLimiterTest {
    @Test fun `elapsed time is not clamped at former thirty second limit`() {
        assertEquals(45_000L, RecordingLimiter().onBytes(16_000 * 2 * 45).elapsedMs)
    }
}
