package me.trion.whispertype.voice

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Deterministic checks for the temporary diagnostics' capture metrics. */
class AsrDiagnosticsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeWav(samples: ShortArray, sampleRate: Int = 16_000): File {
        val data = ByteArray(samples.size * 2)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(data.size)
        return tmp.newFile("clip.wav").apply {
            outputStream().use { it.write(header.array()); it.write(data) }
        }
    }

    @Test
    fun `metrics report duration peak clipping and speech extents`() {
        val file = writeWav(shortArrayOf(0, 16384, -16384, 32767, -32768))
        val m = AsrDiagnostics.pcm16Metrics(file)

        assertEquals(5.0 / 16_000.0, m.durationS, 1e-9)
        assertEquals(1.0, m.peak, 1e-6)
        // Both full-scale samples sit within 2 LSB of the clip threshold.
        assertEquals(0.4, m.clippingRatio, 1e-9)
        assertEquals(0.2, m.silenceRatio, 1e-9)
        // First/last voiced sample: |v| > 0.01 starts at 16384/32768.
        assertEquals(1.0 / 16_000.0, m.firstVoicedS!!, 1e-9)
        assertEquals(4.0 / 16_000.0, m.lastVoicedS!!, 1e-9)
    }

    @Test
    fun `rms of a half-scale sine pair matches hand calculation`() {
        val file = writeWav(shortArrayOf(16384, -16384))
        val m = AsrDiagnostics.pcm16Metrics(file)
        assertEquals(0.5, m.rms, 1e-6)
    }

    @Test
    fun `digital silence has no voiced extent`() {
        val file = writeWav(ShortArray(1_000))
        val m = AsrDiagnostics.pcm16Metrics(file)
        assertEquals(0.0, m.rms, 1e-9)
        assertEquals(null, m.firstVoicedS)
        assertEquals(null, m.lastVoicedS)
    }
}
