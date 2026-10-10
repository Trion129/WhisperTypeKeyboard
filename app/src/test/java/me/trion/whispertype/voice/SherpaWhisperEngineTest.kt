package me.trion.whispertype.voice

import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaWhisperEngineTest {

    private fun resultWith(tokenCount: Int): OfflineRecognizerResult {
        val tokens = Array(tokenCount) { "tok$it" }
        return OfflineRecognizerResult(
            text = tokens.joinToString(" "),
            tokens = tokens,
            timestamps = FloatArray(tokenCount),
            lang = "hi",
            emotion = "",
            event = "",
            durations = FloatArray(tokenCount),
        )
    }

    @Test
    fun `token cap is six tokens per second of audio`() {
        // Values measured against sherpa-onnx v1.13.4 on FLEURS Hindi clips:
        // 9.1 s -> 54 tokens, 13.8 s -> 82 tokens, 15.0 s -> 90 tokens.
        assertEquals(54, SherpaWhisperEngine.decoderTokenCap(145_600, 16_000))
        assertEquals(82, SherpaWhisperEngine.decoderTokenCap(220_800, 16_000))
        assertEquals(90, SherpaWhisperEngine.decoderTokenCap(240_000, 16_000))
        assertEquals(0, SherpaWhisperEngine.decoderTokenCap(0, 16_000))
    }

    @Test
    fun `token cap follows the rounded fbank frame count, not raw seconds`() {
        // Measured with sherpa-onnx v1.13.4 tiny on FLEURS Hindi: 53_340
        // samples (333.375 frames -> 333) and 45_340 (283.375 -> 283) stopped
        // at 19 / 16 tokens, one under raw seconds; 45_439 (283.99 -> 284)
        // and 157_439 (983.99 -> 984) reached 17 / 59.
        assertEquals(19, SherpaWhisperEngine.decoderTokenCap(53_340, 16_000))
        assertEquals(16, SherpaWhisperEngine.decoderTokenCap(45_340, 16_000))
        assertEquals(17, SherpaWhisperEngine.decoderTokenCap(45_439, 16_000))
        assertEquals(59, SherpaWhisperEngine.decoderTokenCap(157_439, 16_000))
        // Clamped at sherpa's 2950-frame input limit.
        assertEquals(177, SherpaWhisperEngine.decoderTokenCap(600_000, 16_000))
    }

    @Test
    fun `retry triggers when the decoder stopped at its token cap`() {
        val nineSeconds = 145_600
        assertTrue(
            SherpaWhisperEngine.shouldRetryInFullWindow(
                resultWith(54), nineSeconds, 16_000
            )
        )
        assertTrue(
            SherpaWhisperEngine.shouldRetryInFullWindow(
                resultWith(60), nineSeconds, 16_000
            )
        )
    }

    @Test
    fun `retry is skipped when the model finished on its own`() {
        // 15 s clip that stopped at 70 tokens: under the 90 token cap, i.e. the
        // model ended the transcript itself.
        assertFalse(
            SherpaWhisperEngine.shouldRetryInFullWindow(
                resultWith(70), 240_000, 16_000
            )
        )
    }

    @Test
    fun `retry is skipped for empty and window-length audio`() {
        assertFalse(
            SherpaWhisperEngine.shouldRetryInFullWindow(resultWith(0), 145_600, 16_000)
        )
        // 29.49 s of audio already uses the largest window sherpa decodes.
        assertFalse(
            SherpaWhisperEngine.shouldRetryInFullWindow(
                resultWith(500), 471_840, 16_000
            )
        )
    }

    @Test
    fun `padded retry replaces the first pass only when it decoded more`() {
        val capped = resultWith(58)
        // Measured on tiny/Hindi: a capped 58-token pass, padded retry gave 11.
        assertSame(capped, SherpaWhisperEngine.preferLonger(capped, resultWith(11)))
        assertSame(capped, SherpaWhisperEngine.preferLonger(capped, resultWith(58)))
        assertSame(capped, SherpaWhisperEngine.preferLonger(capped, resultWith(0)))
        val longer = resultWith(90)
        assertSame(longer, SherpaWhisperEngine.preferLonger(capped, longer))
    }

    @Test
    fun `padding extends short audio to the whisper window`() {
        val oneSecond = FloatArray(16_000) { 0.25f }
        val padded = SherpaWhisperEngine.padToWhisperWindow(oneSecond, 16_000)

        assertEquals(471_840, padded.size)
        assertEquals(0.25f, padded[0])
        assertEquals(0.25f, padded[15_999])
        assertEquals(0f, padded[16_000])
        assertEquals(0f, padded[471_839])
    }

    @Test
    fun `padding leaves long audio untouched`() {
        val window = FloatArray(471_840)
        assertSame(
            window,
            SherpaWhisperEngine.padToWhisperWindow(window, 16_000)
        )
    }
}
