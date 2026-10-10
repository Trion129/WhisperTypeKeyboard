package me.trion.whispertype.voice

import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Replays fixture wavs through the app's REAL pipeline — [WavReader] +
 * [SherpaWhisperEngine] + [ModelCatalog.effectiveLanguage] resolution — on the
 * host JVM using sherpa-onnx's desktop JNI build. Driven by
 * `bench/host_replay.sh`, which supplies the `whispertype.host.*` system
 * properties and the native libraries; skipped otherwise. Output rows match
 * PipelineReplayTest's JSONL schema plus `resolved_language`.
 */
class HostPipelineReplayTest {

    @Test
    fun replayFixtures() {
        val modelId = System.getProperty("whispertype.host.model").orEmpty()
        val modelDir = System.getProperty("whispertype.host.modelDir").orEmpty()
        Assume.assumeTrue("host replay not configured", modelId.isNotEmpty() && modelDir.isNotEmpty())
        val requested = System.getProperty("whispertype.host.language").orEmpty()
        val fixtures = File(System.getProperty("whispertype.host.fixtures").orEmpty())
        val out = File(System.getProperty("whispertype.host.out").orEmpty())

        val dir = File(modelDir)
        val encoder = File(dir, "$modelId-encoder.int8.onnx")
        val decoder = File(dir, "$modelId-decoder.int8.onnx")
        val tokens = File(dir, "$modelId-tokens.txt")
        val language = ModelCatalog.effectiveLanguage(
            modelId,
            requested,
            ModelDownloader.isMultilingualOnnx(encoder),
        )

        val wavs = fixtures.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
            ?.sortedBy { it.name }
            .orEmpty()
        Assume.assumeTrue("no wav files in $fixtures", wavs.isNotEmpty())

        val engine = SherpaWhisperEngine(
            encoder.absolutePath,
            decoder.absolutePath,
            tokens.absolutePath,
            language = language,
        )
        var trace: SherpaWhisperEngine.Trace? = null
        engine.onTrace = { trace = it }
        try {
            out.absoluteFile.parentFile?.mkdirs()
            out.bufferedWriter(Charsets.UTF_8).use { w ->
                for (wav in wavs) {
                    trace = null
                    val t0 = System.nanoTime()
                    val data = WavReader.read(wav)
                    val t1 = System.nanoTime()
                    val text = engine.transcribe(data.samples, data.sampleRate)
                    val t2 = System.nanoTime()
                    val prepMs = (t1 - t0) / 1_000_000
                    val transcribeMs = (t2 - t1) / 1_000_000
                    val tr = trace
                    val duration = data.samples.size.toDouble() / data.sampleRate
                    val row = buildString {
                        append('{')
                        append("\"file\":").append(json(wav.name))
                        append(",\"text\":").append(json(text))
                        append(",\"duration_s\":").append(String.format(java.util.Locale.ROOT, "%.3f", duration))
                        append(",\"prep_ms\":").append(prepMs)
                        append(",\"transcribe_ms\":").append(transcribeMs)
                        append(",\"model_id\":").append(json(modelId))
                        append(",\"language\":").append(json(requested))
                        append(",\"resolved_language\":").append(json(language))
                        append(",\"first_pass_tokens\":").append(tr?.firstPassTokens ?: "null")
                        append(",\"token_cap\":").append(tr?.tokenCap ?: "null")
                        append(",\"hit_token_cap\":").append(tr?.hitTokenCap ?: "null")
                        append(",\"first_decode_ms\":").append(tr?.firstDecodeMs ?: "null")
                        append(",\"retry_decode_ms\":").append(tr?.retryDecodeMs ?: "null")
                        append('}')
                    }
                    w.write(row)
                    w.write("\n")
                    println(
                        "host-replay ${wav.name} $transcribeMs ms " +
                            "tokens=${tr?.firstPassTokens ?: "?"}/${tr?.tokenCap ?: "?"}",
                    )
                }
            }
        } finally {
            engine.release()
        }
    }

    private fun json(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c < ' ' -> append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
