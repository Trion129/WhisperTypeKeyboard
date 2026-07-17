package me.trion.whispertype.voice

import android.content.Context
import ai.onnxruntime.OrtSession
import me.trion.whispertype.util.Prefs
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class LocalAsrEngine(private val context: Context) {
    private val prefs = Prefs(context)
    private val downloader = ModelDownloader(context)
    private val engineRef = AtomicReference<Any?>(null)
    private var loadedModelId: String? = null
    private var initializerSession: OrtSession? = null

    @Synchronized
    fun ensureLoaded(): String? {
        val model = ModelCatalog.byId(prefs.modelId)
        if (!downloader.isInstalled(model)) {
            return "Download a model first (open settings)"
        }
        if (engineRef.get() != null && loadedModelId == model.id) {
            return null
        }
        release()
        return try {
            loadInitializer()
            val dir = downloader.modelFolder(model)
            when (model.kind) {
                AsrModel.Kind.WHISPER -> {
                    val p = model.whisperPrefix
                    val encSession = OnnxRuntime.loadSession(File(dir, "$p-encoder.int8.onnx"))
                    val decSession = OnnxRuntime.loadSession(File(dir, "$p-decoder.int8.onnx"))
                    val tokensFile = File(dir, "$p-tokens.txt")
                    engineRef.set(WhisperEngine(encSession, decSession, initializerSession!!, tokensFile))
                }
                AsrModel.Kind.PARAKEET -> {
                    val modelFile = listOf("model.int8.onnx", "model.onnx")
                        .map { File(dir, it) }
                        .first { it.exists() }
                    val modelSession = OnnxRuntime.loadSession(modelFile)
                    val tokensFile = File(dir, "tokens.txt")
                    engineRef.set(CtcEngine(modelSession, initializerSession!!, tokensFile))
                }
            }
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

        return try {
            val wav = WavReader.read(wavFile)
            val text = when (val engine = engineRef.get()) {
                is WhisperEngine -> engine.transcribe(wav.samples)
                is CtcEngine -> engine.transcribe(wav.samples)
                else -> return Result.Error("Engine not initialized")
            }
            if (text.isBlank()) Result.Error("No speech detected") else Result.Success(text)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Transcription failed")
        }
    }

    @Synchronized
    fun release() {
        val engine = engineRef.getAndSet(null)
        when (engine) {
            is WhisperEngine -> { /* sessions closed by GC or explicit close */ }
            is CtcEngine -> { /* same */ }
        }
        initializerSession?.close()
        initializerSession = null
        loadedModelId = null
    }

    private fun loadInitializer() {
        if (initializerSession != null) return
        context.assets.open("whisper/Whisper_initializer.onnx").use { stream ->
            val bytes = stream.readBytes()
            val tmpFile = File(context.cacheDir, "Whisper_initializer.onnx")
            tmpFile.writeBytes(bytes)
            initializerSession = OnnxRuntime.loadSession(tmpFile)
        }
    }

    sealed class Result {
        data class Success(val text: String) : Result()
        data class Error(val message: String) : Result()
    }
}
