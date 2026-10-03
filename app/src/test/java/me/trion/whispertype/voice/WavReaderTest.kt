package me.trion.whispertype.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Behavior-first coverage of the app's wav preprocessing (plan step 8):
 * what enters sherpa must be reproducible byte for byte, because
 * bench/harness.py mirrors exactly these semantics for the paired baseline.
 */
class WavReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // Fixture builders
    // ------------------------------------------------------------------

    /** Builds a RIFF/WAVE byte array with optional extra chunks before data. */
    private fun wavBytes(
        samples: ShortArray,
        sampleRate: Int = 16_000,
        channels: Int = 1,
        bitsPerSample: Int = 16,
        extraChunks: List<Pair<String, ByteArray>> = emptyList(),
    ): ByteArray {
        val bytesPerSample = bitsPerSample / 8
        val data = ByteArray(samples.size * bytesPerSample)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (bitsPerSample == 16) {
            for (s in samples) buf.putShort(s)
        } else {
            for (s in samples) buf.put((s.toInt() and 0xFF).toByte())
        }

        val payload = java.io.ByteArrayOutputStream()
        fun writeChunk(sink: java.io.ByteArrayOutputStream, id: String, body: ByteArray) {
            sink.write(id.toByteArray(Charsets.US_ASCII))
            sink.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(body.size).array())
            sink.write(body)
        }
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1) // PCM
            .putShort(channels.toShort())
            .putInt(sampleRate)
            .putInt(sampleRate * channels * bytesPerSample)
            .putShort((channels * bytesPerSample).toShort())
            .putShort(bitsPerSample.toShort())
            .array()
        payload.write("WAVE".toByteArray(Charsets.US_ASCII))
        for ((id, chunkBody) in extraChunks) writeChunk(payload, id, chunkBody)
        writeChunk(payload, "fmt ", fmt)
        writeChunk(payload, "data", data)
        val out = java.io.ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(payload.size() + 8).array())
        out.write(payload.toByteArray())
        return out.toByteArray()
    }

    private fun writeWav(name: String, bytes: ByteArray): File =
        tmp.newFile(name).apply { writeBytes(bytes) }

    private fun readBytes(bytes: ByteArray): WavData = WavReader.read(bytes)

    private fun FloatArray.assertCloseTo(expected: FloatArray, delta: Float = 1e-6f) {
        assertEquals("length", expected.size, size)
        for (i in expected.indices) {
            assertEquals("sample $i", expected[i], this[i], delta)
        }
    }

    // ------------------------------------------------------------------
    // Amplitude and length (no accidental sample loss)
    // ------------------------------------------------------------------

    @Test
    fun `pcm16 samples decode to signed float amplitudes`() {
        val samples = shortArrayOf(0, 16384, -16384, 32767, -32768)
        val wav = readBytes(wavBytes(samples))
        wav.samples.assertCloseTo(
            floatArrayOf(0f, 0.5f, -0.5f, 32767f / 32768f, -1f)
        )
    }

    @Test
    fun `length is preserved for every sample`() {
        val samples = ShortArray(9_999) { (it % 251).toShort() }
        val wav = readBytes(wavBytes(samples))
        assertEquals(9_999, wav.samples.size)
    }

    @Test
    fun `stereo channels are averaged into mono`() {
        // decodePcm accumulates into a Double and divides by channels in
        // float, then peak-normalizes: [16383.5, -0.5]/32768 -> /0.4999847.
        val interleaved = shortArrayOf(32767, 0, -32768, 32767)
        val wav = readBytes(wavBytes(interleaved, channels = 2))
        assertEquals(2, wav.samples.size)
        assertEquals(1f, wav.samples[0], 1e-6f)
        assertEquals(-0.5f / 32768f / (16383.5f / 32768f), wav.samples[1], 1e-6f)
    }

    @Test
    fun `8-bit pcm decodes with unsigned offset`() {
        // Byte values 128, 255, 0 -> offsets -128, +127, -128.
        val wav = readBytes(wavBytes(shortArrayOf(128, 255, 0), bitsPerSample = 8))
        wav.samples.assertCloseTo(floatArrayOf(0f, 127f / 128f, -1f))
    }

    // ------------------------------------------------------------------
    // Peak normalization (preprocess)
    // ------------------------------------------------------------------

    @Test
    fun `recording is peak normalized to unit amplitude`() {
        val samples = shortArrayOf(8192, -16384, 4096) // peak 16384 -> 0.5
        val wav = readBytes(wavBytes(samples))
        wav.samples.assertCloseTo(floatArrayOf(0.5f, -1f, 0.25f))
    }

    @Test
    fun `digital silence stays silence without dividing by zero`() {
        val wav = readBytes(wavBytes(ShortArray(100)))
        wav.samples.assertCloseTo(FloatArray(100))
        assertTrue(wav.samples.all { !it.isNaN() })
    }

    @Test
    fun `sample rate in result is always the target rate`() {
        val wav = readBytes(wavBytes(shortArrayOf(100), sampleRate = 16_000))
        assertEquals(16_000, wav.sampleRate)
    }

    // ------------------------------------------------------------------
    // Resampling
    // ------------------------------------------------------------------

    @Test
    fun `8 khz audio is upsampled by linear interpolation`() {
        // Two samples at 8 kHz -> 4 samples at 16 kHz, then peak-normalized:
        // input [0, 0.5] resamples to [0, 0.25, 0.5, 0.5], peak 0.5 -> /0.5.
        val wav = readBytes(wavBytes(shortArrayOf(0, 16384), sampleRate = 8_000))
        wav.samples.assertCloseTo(floatArrayOf(0f, 0.5f, 1f, 1f))
    }

    @Test
    fun `44_1 khz audio is downsampled to 16 khz`() {
        // 4410 samples at 44.1 kHz -> exactly 1600 samples at 16 kHz.
        val samples = ShortArray(4_410) { 0 }
        val wav = readBytes(wavBytes(samples, sampleRate = 44_100))
        assertEquals(1_600, wav.samples.size)
        assertEquals(16_000, wav.sampleRate)
    }

    @Test
    fun `resampled audio keeps its peak normalization`() {
        // [4096, -8192] at 8 kHz -> [0.125, -0.25]; 16 kHz linear interp at
        // half-steps gives [0.125, -0.0625, -0.25, -0.25]; peak 0.25 -> /0.25.
        val wav = readBytes(wavBytes(shortArrayOf(4096, -8192), sampleRate = 8_000))
        wav.samples.assertCloseTo(floatArrayOf(0.5f, -0.25f, -1f, -1f))
    }

    // ------------------------------------------------------------------
    // Container parsing
    // ------------------------------------------------------------------

    @Test
    fun `extra chunks before fmt and data are skipped`() {
        val listChunk = "INFOabc".toByteArray(Charsets.US_ASCII)
        val wav = readBytes(
            wavBytes(shortArrayOf(16384, -16384), extraChunks = listOf("LIST" to listChunk))
        )
        wav.samples.assertCloseTo(floatArrayOf(1f, -1f))
    }

    @Test
    fun `non-pcm format is rejected`() {
        val raw = wavBytes(shortArrayOf(0))
        // audioFormat 3 (IEEE float) instead of 1
        val patched = raw.copyOf().also { it[20] = 3 }
        assertThrows(IllegalArgumentException::class.java) { readBytes(patched) }
    }

    @Test
    fun `missing data chunk is rejected`() {
        val fmtOnly = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            .array()
        assertThrows(IllegalArgumentException::class.java) { readBytes(fmtOnly) }
    }

    @Test
    fun `non-riff file is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            readBytes("not a wav".toByteArray())
        }
    }

    // ------------------------------------------------------------------
    // File entry point
    // ------------------------------------------------------------------

    @Test
    fun `read from file matches read from bytes`() {
        val samples = shortArrayOf(16384, -8192, 0)
        val file = writeWav("clip.wav", wavBytes(samples))
        val fromFile = WavReader.read(file)
        val fromBytes = readBytes(wavBytes(samples))
        assertEquals(fromBytes.samples.size, fromFile.samples.size)
        for (i in samples.indices) {
            assertEquals(fromBytes.samples[i], fromFile.samples[i], 1e-6f)
        }
    }
}
