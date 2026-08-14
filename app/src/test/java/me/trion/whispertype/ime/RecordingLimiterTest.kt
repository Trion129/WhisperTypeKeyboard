package me.trion.whispertype.ime

import org.junit.Assert.*
import org.junit.Test

class RecordingLimiterTest {

    private val limiter = RecordingLimiter()

    private fun bytesForMs(ms: Long, sampleRate: Int = 16_000): Int {
        return ((ms * sampleRate * 2L) / 1000L).toInt()
    }

    @Test
    fun `zero bytes is listening with full remaining`() {
        val tick = limiter.onBytes(0)
        assertEquals(0L, tick.elapsedMs)
        assertEquals(30_000L, tick.remainingMs)
        assertEquals(RecordingPhase.LISTENING, tick.phase)
    }

    @Test
    fun `sixteen kilohertz sixteen bit two seconds is two thousand ms`() {
        val tick = limiter.onBytes(16_000 * 2 * 2)
        assertEquals(2_000L, tick.elapsedMs)
        assertEquals(28_000L, tick.remainingMs)
        assertEquals(RecordingPhase.LISTENING, tick.phase)
    }

    @Test
    fun `just under warn stays listening`() {
        val tick = limiter.onBytes(bytesForMs(24_999))
        assertEquals(RecordingPhase.LISTENING, tick.phase)
        assertTrue(tick.elapsedMs < 25_000)
    }

    @Test
    fun `twenty five seconds is warn`() {
        val tick = limiter.onBytes(bytesForMs(25_000))
        assertEquals(25_000L, tick.elapsedMs)
        assertEquals(5_000L, tick.remainingMs)
        assertEquals(RecordingPhase.WARN, tick.phase)
    }

    @Test
    fun `thirty seconds is limit with zero remaining`() {
        val tick = limiter.onBytes(bytesForMs(30_000))
        assertEquals(30_000L, tick.elapsedMs)
        assertEquals(0L, tick.remainingMs)
        assertEquals(RecordingPhase.LIMIT, tick.phase)
    }

    @Test
    fun `elapsed never exceeds max even if more bytes arrive`() {
        val tick = limiter.onBytes(bytesForMs(45_000))
        assertEquals(30_000L, tick.elapsedMs)
        assertEquals(0L, tick.remainingMs)
        assertEquals(RecordingPhase.LIMIT, tick.phase)
    }

    @Test
    fun `zero sample rate does not crash`() {
        val tick = limiter.onBytes(1000, sampleRate = 0)
        assertEquals(0L, tick.elapsedMs)
        assertEquals(30_000L, tick.remainingMs)
        assertEquals(RecordingPhase.LISTENING, tick.phase)
    }
}
