package me.trion.whispertype.voice

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Plan step 1.4 "current Android pipeline" arm: replays wav fixtures pushed
 * to `/data/local/tmp/whispertype-bench` through this build's real
 * [WavReader] (peak normalization included) and [SherpaWhisperEngine]
 * (greedy_search + token-cap retry), and writes a JSONL report the desktop
 * harness can be compared against line by line.
 *
 * Setup (see bench/README.md):
 * ```
 * adb push bench/fixtures /data/local/tmp/whispertype-bench/fixtures
 * adb push /tmp/whisper-check/models/small /data/local/tmp/whispertype-bench/models/small
 * adb shell "cat > /data/local/tmp/whispertype-bench/manifest.json" < manifest.json
 * adb shell chmod -R a+rX /data/local/tmp/whispertype-bench
 * ```
 * with manifest.json: `{"model_id":"small","language":"hi",
 * "fixtures_dir":"/data/local/tmp/whispertype-bench/fixtures",
 * "model_dir":"/data/local/tmp/whispertype-bench/models/small"}`
 *
 * The report lands in the app's external files dir (`bench-out/results.jsonl`);
 * pull it with `adb shell run-as me.trion.whispertype cat files/bench-out/results.jsonl`
 * or plain `adb pull`. Skips silently (assumption) when no manifest is pushed,
 * so ordinary test runs are unaffected. Timings are for the running device
 * (emulator x86_64 is not phone-representative); transcripts, token counts
 * and truncation flags are the paired quality evidence.
 */
@RunWith(AndroidJUnit4::class)
class PipelineReplayTest {

    @Test
    fun replayFixturesThroughRealPipeline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File("/data/local/tmp/whispertype-bench")
        val manifest = File(root, "manifest.json")
        assumeTrue("No /data/local/tmp/whispertype-bench/manifest.json pushed; skipping replay", manifest.exists())

        val cfg = JSONObject(manifest.readText())
        val modelId = cfg.getString("model_id")
        val language = cfg.optString("language", "")
        val modelDir = File(cfg.optString("model_dir", "$root/models/$modelId"))
        val fixturesDir = File(cfg.optString("fixtures_dir", "$root/fixtures"))

        val wavs = fixturesDir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
            ?.sortedBy { it.name }
            ?: emptyList()
        assumeTrue("No wav fixtures in $fixturesDir; skipping replay", wavs.isNotEmpty())

        val engine = SherpaWhisperEngine(
            encoderPath = File(modelDir, "$modelId-encoder.int8.onnx").absolutePath,
            decoderPath = File(modelDir, "$modelId-decoder.int8.onnx").absolutePath,
            tokensPath = File(modelDir, "$modelId-tokens.txt").absolutePath,
            language = language,
        )
        try {
            var lastTrace: SherpaWhisperEngine.Trace? = null
            engine.onTrace = { lastTrace = it }

            val outDir = (context.getExternalFilesDir(null) ?: context.filesDir)
                .resolve("bench-out")
            outDir.mkdirs()
            val out = outDir.resolve("results.jsonl")
            out.bufferedWriter().use { writer ->
                for (wav in wavs) {
                    lastTrace = null
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
                        .put("model_id", modelId)
                        .put("language", language)
                    lastTrace?.let { t ->
                        row.put("first_pass_tokens", t.firstPassTokens)
                            .put("token_cap", t.tokenCap)
                            .put("hit_token_cap", t.hitTokenCap)
                            .put("retried_padded", t.retriedPadded)
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
