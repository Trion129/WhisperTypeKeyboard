package com.whispertype.keyboard.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import com.whispertype.keyboard.util.Prefs
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class LocalAsrEngine(private val context: Context) {
    private val prefs = Prefs(context)
    private val downloader = ModelDownloader(context)
    private val recognizerRef = AtomicReference<OfflineRecognizer?>(null)
    private var loadedModelId: String? = null

    @Synchronized
    fun ensureLoaded(): String? {
        val model = ModelCatalog.byId(prefs.modelId)
        if (!downloader.isInstalled(model)) {
            return "Download a model first (open settings)"
        }
        if (recognizerRef.get() != null && loadedModelId == model.id) {
            return null
        }
        release()
        return try {
            val config = OfflineRecognizerConfig(
                modelConfig = buildModelConfig(model),
                decodingMethod = "greedy_search"
            )
            val recognizer = OfflineRecognizer(assetManager = null, config = config)
            recognizerRef.set(recognizer)
            loadedModelId = model.id
            null
        } catch (e: Exception) {
            "Failed to load model: ${e.message}"
        }
    }

    fun isModelReady(): Boolean = downloader.isInstalled(ModelCatalog.byId(prefs.modelId))

    fun currentModelTitle(): String = ModelCatalog.byId(prefs.modelId).title

    fun transcribeWav(wavFile: File): Result {
        val loadError = ensureLoaded()
        if (loadError != null) return Result.Error(loadError)
        val recognizer = recognizerRef.get() ?: return Result.Error("Recognizer not ready")

        return try {
            val wave = WaveReader.readWave(wavFile.absolutePath)
            if (wave.samples.isEmpty()) return Result.Error("No speech detected")
            val stream = recognizer.createStream()
            stream.acceptWaveform(wave.samples, wave.sampleRate)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            stream.release()
            if (text.isEmpty()) Result.Error("No speech detected") else Result.Success(text)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Transcription failed")
        }
    }

    @Synchronized
    fun release() {
        recognizerRef.getAndSet(null)?.release()
        loadedModelId = null
    }

    private fun buildModelConfig(model: AsrModel): OfflineModelConfig {
        val dir = downloader.modelFolder(model)
        return when (model.kind) {
            AsrModel.Kind.WHISPER -> {
                val p = model.whisperPrefix
                OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = File(dir, "$p-encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "$p-decoder.int8.onnx").absolutePath,
                        language = "en",
                        task = "transcribe"
                    ),
                    tokens = File(dir, "$p-tokens.txt").absolutePath,
                    modelType = "whisper",
                    numThreads = 2,
                    provider = "cpu",
                    debug = false
                )
            }
            AsrModel.Kind.PARAKEET -> {
                val modelFile = listOf("model.int8.onnx", "model.onnx")
                    .map { File(dir, it) }
                    .first { it.exists() }
                OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(
                        model = modelFile.absolutePath
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                    debug = false
                )
            }
        }
    }

    sealed class Result {
        data class Success(val text: String) : Result()
        data class Error(val message: String) : Result()
    }
}
