package me.trion.whispertype.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmSegmenterTest {
    @Test fun `hard cuts preserve all samples and honor model maximum`() {
        val segmenter = PcmSegmenter(5_000)
        val pcm = ByteArray(16_000 * 2 * 11) { if (it % 2 == 1) 0x20 else 0 }
        val cuts = segmenter.append(pcm) + listOfNotNull(segmenter.finish())
        assertEquals(listOf(5_000, 5_000, 1_000), cuts.map { it.size / 32 })
        assertArrayEquals(pcm, cuts.fold(ByteArray(0)) { acc, next -> acc + next })
    }

    @Test fun `silence after soft target cuts at silence window boundary`() {
        val segmenter = PcmSegmenter(10_000)
        val voicedBytes = 7_000 * 32
        val pcm = ByteArray(10_000 * 32)
        var i = 0
        while (i < voicedBytes) {
            pcm[i] = 0x20
            pcm[i + 1] = 0x03
            i += 2
        }
        val result = segmenter.append(pcm)
        assertEquals(1, result.size)
        assertEquals(7_120 * 32, result.single().size)
        assertArrayEquals(pcm, result.single() + segmenter.finish()!!)
    }

    @Test fun `joined segments capitalize only the transcript start`() {
        assertEquals("We went to the Store", joinTranscriptSegments(listOf("we went", "to the Store")))
    }

    @Test fun `handoff emits bounded chunks in order then final partial exactly once`() {
        val handedOff = mutableListOf<ByteArray>()
        val handoff = PcmSegmentHandoff(5_000) { pcm, _ -> handedOff += pcm }
        val pcm = ByteArray(16_000 * 2 * 6) { if (it % 2 == 1) 0x20 else 0 }
        handoff.append(pcm, 0, 4_000 * 32)
        assertEquals(0, handedOff.size)
        handoff.append(pcm, 4_000 * 32, 2_000 * 32)
        assertEquals(listOf(5_000), handedOff.map { it.size / 32 })
        handoff.finish()
        handoff.finish()
        handoff.append(byteArrayOf(1, 2))
        assertEquals(6_000L * 32, handoff.capturedBytes())
        assertEquals(listOf(5_000, 1_000), handedOff.map { it.size / 32 })
        assertEquals(pcm.toList(), (handedOff[0] + handedOff[1]).toList())
        assertEquals(pcm.size.toLong(), handoff.capturedBytes())
    }

    @Test fun `cancel drops unhanded tail`() {
        val handedOff = mutableListOf<ByteArray>()
        val handoff = PcmSegmentHandoff(5_000) { pcm, _ -> handedOff += pcm }
        handoff.append(ByteArray(16_000 * 2) { if (it % 2 == 1) 0x20 else 0 })
        handoff.cancel()
        handoff.append(byteArrayOf(1, 2))
        assertEquals(32_000L, handoff.capturedBytes())
        assertEquals(0, handedOff.size)
    }
}
