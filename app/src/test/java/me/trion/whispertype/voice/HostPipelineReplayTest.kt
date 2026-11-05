package me.trion.whispertype.voice

import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.Locale

/** Host-JVM replay through the same descriptor and adapter factory as the app. */
class HostPipelineReplayTest {
    @Test
    fun replayFixtures() {
        val modelId = System.getProperty("whispertype.host.model").orEmpty()
        val modelPath = System.getProperty("whispertype.host.modelDir").orEmpty()
        Assume.assumeTrue("host replay not configured", modelId.isNotEmpty() && modelPath.isNotEmpty())
        val spec = requireNotNull(ModelCatalog.byId(modelId)) { "Unknown catalog model: $modelId" }
        val directory = File(modelPath)
        if (System.getenv("WHISPERTYPE_FETCH_ARTIFACTS") == "1") {
            directory.mkdirs()
            ModelDownloader.fetchArtifacts(spec, directory)
        }
        if (System.getenv("WHISPERTYPE_FETCH_ONLY") == "1") return
        val model = requireNotNull(ModelDownloader.resolveModelDirectory(directory, spec)) {
            "Model files do not match ${spec.id} descriptor at $modelPath"
        }

        val requested = System.getProperty("whispertype.host.language").orEmpty()
        val language = spec.languagePolicy.effectiveLanguage(requested)
        val fixtures = File(System.getProperty("whispertype.host.fixtures").orEmpty())
        val out = File(System.getProperty("whispertype.host.out").orEmpty())
        val wavs = fixtures.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
            ?.sortedBy { it.name }
            .orEmpty()
        require(wavs.isNotEmpty()) { "No WAV files in $fixtures" }

        val engine = AsrEngineFactory.create(model, requested)
        val whisper = engine as? SherpaWhisperEngine
        var trace: SherpaWhisperEngine.Trace? = null
        whisper?.onTrace = { trace = it }
        try {
            out.absoluteFile.parentFile?.mkdirs()
            out.bufferedWriter(Charsets.UTF_8).use { writer ->
                for (wav in wavs) {
                    trace = null
                    whisper?.onRecordingStart()
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
                        append(",\"duration_s\":").append(String.format(Locale.ROOT, "%.3f", duration))
                        append(",\"prep_ms\":").append(prepMs)
                        append(",\"transcribe_ms\":").append(transcribeMs)
                        append(",\"model_id\":").append(json(spec.id))
                        append(",\"model_title\":").append(json(spec.title))
                        append(",\"revision\":").append(json(spec.revision))
                        append(",\"language\":").append(json(requested))
                        append(",\"resolved_language\":").append(json(language))
                        if (tr != null) {
                            append(",\"first_pass_tokens\":").append(tr.firstPassTokens)
                            append(",\"token_cap\":").append(tr.tokenCap)
                            append(",\"hit_token_cap\":").append(tr.hitTokenCap)
                            append(",\"used_retry\":").append(tr.usedRetry)
                            append(",\"padded_up_front\":").append(tr.paddedUpFront)
                            append(",\"detected_language\":").append(json(tr.detectedLanguage))
                            append(",\"empty_tokens\":").append(tr.emptyTokenCount)
                            append(",\"first_decode_ms\":").append(tr.firstDecodeMs)
                            append(",\"retry_decode_ms\":").append(tr.retryDecodeMs)
                            append(",\"split_decodes\":").append(tr.splitDecodes)
                        }
                        append('}')
                    }
                    writer.write(row)
                    writer.write("\n")
                    println("host-replay ${wav.name} $transcribeMs ms${tr?.let { " tokens=${it.firstPassTokens}/${it.tokenCap}" } ?: ""}")
                }
            }
        } finally {
            engine.release()
        }
    }

    private fun json(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}
