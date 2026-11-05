package me.trion.whispertype.voice

import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaWhisperEngineTest {

    private fun resultWith(tokenCount: Int, lang: String = "hi"): OfflineRecognizerResult {
        val tokens = Array(tokenCount) { "tok$it" }
        return OfflineRecognizerResult(
            text = tokens.joinToString(" "),
            tokens = tokens,
            timestamps = FloatArray(tokenCount),
            lang = lang,
            emotion = "",
            event = "",
            durations = FloatArray(tokenCount),
        )
    }

    /** Canned decodes, one per call, so a test can count real decodes. */
    private class FakeBackend(vararg results: OfflineRecognizerResult) :
        SherpaWhisperEngine.DecodeBackend {
        private val canned = results.toList()
        val inputs = mutableListOf<FloatArray>()

        override fun decode(
            samples: FloatArray,
            sampleRate: Int,
        ): OfflineRecognizerResult {
            inputs += samples
            return canned[(inputs.size - 1).coerceAtMost(canned.lastIndex)]
        }

        override fun release() = Unit
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
    fun `empty token strings are counted as dropped byte tokens`() {
        // sherpa-onnx 1.13.x returned "" for Whisper byte-fallback tokens,
        // deleting every character outside the standalone-token set.
        val tokens = arrayOf("क", "", "", "म", "", "े")
        val result = OfflineRecognizerResult(
            text = "कमे",
            tokens = tokens,
            timestamps = FloatArray(tokens.size),
            lang = "hi",
            emotion = "",
            event = "",
            durations = FloatArray(tokens.size),
        )
        assertEquals(3, SherpaWhisperEngine.emptyTokenCount(result))
        assertEquals(0, SherpaWhisperEngine.emptyTokenCount(resultWith(4)))
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

    @Test
    fun `dense set covers the plan scripts and leaves latin out`() {
        assertTrue(isDenseLanguage("hi"))
        assertTrue(isDenseLanguage("ZH"))
        assertTrue(isDenseLanguage("he"))
        assertFalse(isDenseLanguage("en"))
        assertFalse(isDenseLanguage("es"))
        assertFalse(isDenseLanguage(""))
    }

    @Test
    fun `dense language pads up front and decodes once without retry`() {
        // 54 tokens is exactly the cap of 9.1 s audio, i.e. the retry would
        // fire on a real-length decode.
        val backend = FakeBackend(resultWith(54))
        val engine = SherpaWhisperEngine("hi", backend)
        var trace: SherpaWhisperEngine.Trace? = null
        engine.onTrace = { trace = it }

        engine.transcribe(FloatArray(145_600), 16_000)

        assertEquals(1, backend.inputs.size)
        assertEquals(471_840, backend.inputs[0].size)
        assertEquals(145_600, trace!!.sampleCount)
        assertEquals(176, trace!!.tokenCap)
        assertTrue(trace!!.paddedUpFront)
        assertFalse(trace!!.usedRetry)
        assertFalse(trace!!.hitTokenCap)
    }

    @Test
    fun `light language decodes real-length audio once without retry`() {
        val backend = FakeBackend(resultWith(20, lang = "es"))
        val engine = SherpaWhisperEngine("es", backend)
        var trace: SherpaWhisperEngine.Trace? = null
        engine.onTrace = { trace = it }

        engine.transcribe(FloatArray(145_600), 16_000)

        assertEquals(1, backend.inputs.size)
        assertEquals(145_600, backend.inputs[0].size)
        assertFalse(trace!!.paddedUpFront)
        assertFalse(trace!!.usedRetry)
    }

    @Test
    fun `auto detect retries the first part then pads later parts until reset`() {
        val backend = FakeBackend(
            resultWith(54, lang = ""),
            resultWith(60, lang = "hi"),
            resultWith(90, lang = "hi"),
            resultWith(54, lang = ""),
            resultWith(70, lang = "hi"),
        )
        val engine = SherpaWhisperEngine("", backend)
        val traces = mutableListOf<SherpaWhisperEngine.Trace>()
        engine.onTrace = { traces += it }
        val audio = FloatArray(145_600)

        engine.transcribe(audio, 16_000)
        assertEquals(2, backend.inputs.size)
        assertFalse(traces[0].paddedUpFront)
        assertTrue(traces[0].hitTokenCap)
        assertTrue(traces[0].usedRetry)
        assertEquals("hi", traces[0].detectedLanguage)

        // Same recording: detection from the retry result pads the next part.
        engine.transcribe(audio, 16_000)
        assertEquals(3, backend.inputs.size)
        assertEquals(471_840, backend.inputs[2].size)
        assertTrue(traces[1].paddedUpFront)
        assertFalse(traces[1].usedRetry)

        engine.onRecordingStart()
        engine.transcribe(audio, 16_000)
        assertEquals(5, backend.inputs.size)
        assertFalse(traces[2].paddedUpFront)
        assertTrue(traces[2].usedRetry)
    }

    @Test
    fun `dense part that fills the window is split and each half decoded padded`() {
        val backend = FakeBackend(resultWith(176), resultWith(80), resultWith(70))
        val engine = SherpaWhisperEngine("hi", backend)
        var trace: SherpaWhisperEngine.Trace? = null
        engine.onTrace = { trace = it }

        val text = engine.transcribe(FloatArray(20 * 16_000), 16_000)

        assertEquals(3, backend.inputs.size)
        assertTrue(backend.inputs.all { it.size == 471_840 })
        assertEquals(2, trace!!.splitDecodes)
        assertTrue(text.startsWith("Tok0 tok1"))
        assertTrue(text.endsWith("tok69"))
    }

    @Test
    fun `split stops after two levels`() {
        val backend = FakeBackend(resultWith(176))
        val engine = SherpaWhisperEngine("hi", backend)
        var trace: SherpaWhisperEngine.Trace? = null
        engine.onTrace = { trace = it }

        engine.transcribe(FloatArray(20 * 16_000), 16_000)

        assertEquals(7, backend.inputs.size)
        assertEquals(6, trace!!.splitDecodes)
    }

    @Test
    fun `repetition loop that fills the window is not split`() {
        val loop = Array(176) { "वो" }
        val backend = FakeBackend(
            OfflineRecognizerResult(
                text = loop.joinToString(" "), tokens = loop, timestamps = FloatArray(176),
                lang = "hi", emotion = "", event = "", durations = FloatArray(176),
            )
        )
        val engine = SherpaWhisperEngine("hi", backend)

        engine.transcribe(FloatArray(20 * 16_000), 16_000)

        assertEquals(1, backend.inputs.size)
    }

    @Test
    fun `quietest cut lands on the silence in the middle third`() {
        val audio = FloatArray(30 * 16_000) { 0.5f }
        for (i in 14 * 16_000 until 15 * 16_000) audio[i] = 0f
        val cut = SherpaWhisperEngine.quietestCut(audio, 16_000)
        assertTrue(cut in 14 * 16_000 until 15 * 16_000)
    }
}
