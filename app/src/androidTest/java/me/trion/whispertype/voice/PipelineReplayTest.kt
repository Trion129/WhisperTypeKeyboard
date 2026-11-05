package me.trion.whispertype.voice

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Replays fixtures through WavReader and the application's descriptor-selected adapter. */
@RunWith(AndroidJUnit4::class)
class PipelineReplayTest {
    @Test
    fun replayFixturesThroughRealPipeline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File("/data/local/tmp/whispertype-bench")
        val manifest = File(root, "manifest.json")
        assumeTrue("No replay manifest pushed; skipping replay", manifest.exists())

        val cfg = JSONObject(manifest.readText())
        val modelId = cfg.getString("model_id")
        val requested = cfg.optString("language", "")
        val spec = ModelCatalog.byId(modelId)
        assumeTrue("Unknown catalog model: $modelId", spec != null)
        val modelDir = File(cfg.optString("model_dir", "$root/models/$modelId"))
        val model = ModelDownloader.resolveModelDirectory(modelDir, spec!!)
        assumeTrue("Model files do not match descriptor ${spec.id}", model != null)
        val fixturesDir = File(cfg.optString("fixtures_dir", "$root/fixtures"))

        val wavs = fixturesDir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
            ?.sortedBy { it.name }
            ?: emptyList()
        assumeTrue("No wav fixtures in $fixturesDir; skipping replay", wavs.isNotEmpty())

        val engine = AsrEngineFactory.create(model!!, requested)
        val whisper = engine as? SherpaWhisperEngine
        try {
            val outDir = (context.getExternalFilesDir(null) ?: context.filesDir).resolve("bench-out")
            outDir.mkdirs()
            val out = outDir.resolve("results.jsonl")
            out.bufferedWriter().use { writer ->
                for (wav in wavs) {
                    var trace: SherpaWhisperEngine.Trace? = null
                    whisper?.onTrace = { trace = it }
                    val prepStart = System.nanoTime()
                    val decoded = WavReader.read(wav)
                    val prepMs = (System.nanoTime() - prepStart) / 1_000_000L
                    val asrStart = System.nanoTime()
                    val text = engine.transcribe(decoded.samples, decoded.sampleRate)
                    val asrMs = (System.nanoTime() - asrStart) / 1_000_000L

                    val row = JSONObject()
                        .put("file", wav.name)
                        .put("text", text)
                        .put("duration_s", decoded.samples.size / decoded.sampleRate.toDouble())
                        .put("prep_ms", prepMs)
                        .put("transcribe_ms", asrMs)
                        .put("model_id", spec.id)
                        .put("model_title", spec.title)
                        .put("revision", spec.revision)
                        .put("language", requested)
                        .put("resolved_language", spec.languagePolicy.effectiveLanguage(requested))
                    trace?.let { t ->
                        row.put("first_pass_tokens", t.firstPassTokens)
                            .put("token_cap", t.tokenCap)
                            .put("hit_token_cap", t.hitTokenCap)
                            .put("first_decode_ms", t.firstDecodeMs)
                            .put("retry_decode_ms", t.retryDecodeMs)
                    }
                    writer.write(row.toString())
                    writer.write("\n")
                    writer.flush()
                    Log.i(TAG, "replayed ${wav.name} (transcribe ${asrMs} ms)")
                }
            }
            Log.i(TAG, "report at ${out.absolutePath}")
        } finally {
            engine.release()
        }
    }

    private companion object {
        const val TAG = "WhisperTypeDiag"
    }
}
