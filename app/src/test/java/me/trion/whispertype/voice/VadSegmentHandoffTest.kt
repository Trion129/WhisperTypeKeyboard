package me.trion.whispertype.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadSegmentHandoffTest {
    private class FakeDetector : SpeechDetector {
        val segments = ArrayDeque<FloatArray>()
        val accepted = ArrayList<FloatArray>()
        var speech = false
        var flushes = 0
        var resets = 0
        var released = false
        var failFlush = false

        /** A segment that a hard flush produces, consumed once. */
        var flushSegment: FloatArray? = null

        override fun accept(samples: FloatArray) {
            accepted += samples
        }

        override fun hasSegment(): Boolean = segments.isNotEmpty()
        override fun takeSegment(): FloatArray = segments.first()
        override fun pop() {
            segments.removeFirst()
        }

        override fun flush() {
            flushes++
            if (failFlush) throw IllegalStateException("flush failed")
            flushSegment?.let {
                segments.add(it)
                flushSegment = null
            }
        }

        override fun reset() {
            resets++
        }

        override fun release() {
            released = true
        }

        override fun isSpeechDetected(): Boolean = speech
    }

    private fun handoff(
        detector: FakeDetector,
        maxSegmentMs: Long = 28_000,
        out: MutableList<Pair<ByteArray, SegmentBoundary>> = mutableListOf(),
    ) = VadSegmentHandoff(detector, maxSegmentMs, 16_000) { pcm, boundary -> out += pcm to boundary }

    @Test
    fun `delivers the detected segment as PCM16 at stop`() {
        val detector = FakeDetector().apply {
            segments.add(floatArrayOf(0f, 0.5f, -0.5f))
        }
        val out = mutableListOf<Pair<ByteArray, SegmentBoundary>>()
        val handoff = handoff(detector, out = out)

        handoff.append(ByteArray(320), 0, 320)
        assertTrue(out.isEmpty())
        handoff.finish()

        assertEquals(1, out.size)
        val pcm = out[0].first
        assertEquals(6, pcm.size)
        assertEquals(0, pcm[0].toInt())
        assertEquals(0, pcm[1].toInt())
        assertEquals(0x3f, pcm[3].toInt() and 0xff)
    }

    @Test
    fun `hard cap flushes the current segment and resets the detector`() {
        val detector = FakeDetector().apply { speech = true }
        val handoff = handoff(detector, maxSegmentMs = 100)

        handoff.append(ByteArray(3_200), 0, 3_200)

        assertEquals(1, detector.flushes)
        assertEquals(1, detector.resets)
    }

    @Test
    fun `silence does not reach the hard cap`() {
        val detector = FakeDetector().apply { speech = false }
        val handoff = handoff(detector, maxSegmentMs = 100)

        handoff.append(ByteArray(3_200), 0, 3_200)

        assertEquals(0, detector.flushes)
    }

    @Test
    fun `finish flushes the last segment and releases the detector`() {
        val detector = FakeDetector().apply {
            speech = true
            segments.add(floatArrayOf(0.25f))
        }
        val out = mutableListOf<Pair<ByteArray, SegmentBoundary>>()
        val handoff = handoff(detector, out = out)

        handoff.finish()

        assertEquals(1, detector.flushes)
        assertTrue(detector.released)
        assertEquals(1, out.size)
    }

    @Test
    fun `cancel releases the detector without delivering`() {
        val detector = FakeDetector()
        val out = mutableListOf<Pair<ByteArray, SegmentBoundary>>()
        val handoff = handoff(detector, out = out)

        handoff.cancel()

        assertTrue(detector.released)
        assertTrue(out.isEmpty())
        assertFalse(detector.flushes > 0)
    }

    @Test
    fun `finish releases detector when flush fails`() {
        val detector = FakeDetector().apply { failFlush = true }
        val handoff = handoff(detector)
        try {
            handoff.finish()
            org.junit.Assert.fail("Expected failure")
        } catch (_: IllegalStateException) {
            assertTrue(detector.released)
        }
    }

    @Test
    fun `a segment that ended on a natural pause reports a pause boundary`() {
        val detector = FakeDetector().apply {
            segments.add(floatArrayOf(0.25f))
        }
        val out = mutableListOf<Pair<ByteArray, SegmentBoundary>>()
        val handoff = handoff(detector, out = out)

        handoff.append(ByteArray(320), 0, 320)
        handoff.finish()

        assertEquals(SegmentBoundary.Pause, out.single().second)
    }

    @Test
    fun `a segment created by a hard flush reports a hard boundary`() {
        val detector = FakeDetector().apply {
            speech = true
            flushSegment = floatArrayOf(0.25f)
        }
        val out = mutableListOf<Pair<ByteArray, SegmentBoundary>>()
        val handoff = handoff(detector, maxSegmentMs = 100, out = out)

        handoff.append(ByteArray(3_200), 0, 3_200)
        handoff.finish()

        assertEquals(1, out.size)
        assertEquals(SegmentBoundary.Hard, out.single().second)
    }
}
