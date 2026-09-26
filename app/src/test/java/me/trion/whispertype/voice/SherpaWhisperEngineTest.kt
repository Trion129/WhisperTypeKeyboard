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
        // 30 s of audio already uses the largest window sherpa decodes.
        assertFalse(
            SherpaWhisperEngine.shouldRetryInFullWindow(
                resultWith(500), 480_000, 16_000
            )
        )
    }

    @Test
    fun `padding extends short audio to the whisper window`() {
        val oneSecond = FloatArray(16_000) { 0.25f }
        val padded = SherpaWhisperEngine.padToWhisperWindow(oneSecond, 16_000)

        assertEquals(480_000, padded.size)
        assertEquals(0.25f, padded[0])
        assertEquals(0.25f, padded[15_999])
        assertEquals(0f, padded[16_000])
        assertEquals(0f, padded[479_999])
    }

    @Test
    fun `padding leaves long audio untouched`() {
        val thirtySeconds = FloatArray(480_000)
        assertSame(
            thirtySeconds,
            SherpaWhisperEngine.padToWhisperWindow(thirtySeconds, 16_000)
        )
    }
}
