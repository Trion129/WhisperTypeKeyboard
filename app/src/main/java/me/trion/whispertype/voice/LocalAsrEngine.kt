package me.trion.whispertype.voice

import android.content.Context
import android.util.Log
import me.trion.whispertype.util.Prefs
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

class LocalAsrEngine(private val context: Context) {
    private val downloader = ModelDownloader(context)
    private var engine: WhisperEngine? = null

    fun ensureLoaded(): String? = synchronized(engineLock) {
        if (!downloader.isInstalled()) return "Download a model first (open settings)"
        if (engine != null) return null
        return try {
            engine = WhisperEngine(
                initializerFile = downloader.initializerFile,
                encoderFile = downloader.encoderFile,
                decoderFile = downloader.decoderFile,
                cacheInitFile = downloader.cacheInitFile,
                detokenizerFile = downloader.detokenizerFile,
            )
            synchronized(loadedEngines) { loadedEngines.add(this) }
            null
        } catch (e: Exception) {
            Log.e("WhisperType", "Failed to load model", e)
            "Failed to load model: ${e.message}"
        }
    }

    fun isModelReady(): Boolean = downloader.isInstalled()

    fun currentModelTitle(): String {
        val prefs = Prefs(context)
        return when (prefs.modelSource) {
            Prefs.MODEL_SOURCE_IMPORT -> "Imported model"
            else -> ModelCatalog.MODEL_TITLE
        }
    }

    fun transcribeWav(wavFile: File): Result = synchronized(engineLock) {
        val loadError = ensureLoaded()
        if (loadError != null) return Result.Error(loadError)
        return try {
            val text = engine!!.transcribe(WavReader.read(wavFile).samples)
            if (text.isBlank()) Result.Error("No speech detected") else Result.Success(text)
        } catch (e: Exception) {
            Log.e("WhisperType", "Transcription failed", e)
            Result.Error(e.message ?: "Transcription failed")
        }
    }

    fun release() = synchronized(engineLock) {
        engine?.close()
        engine = null
        synchronized(loadedEngines) { loadedEngines.remove(this) }
    }

    sealed class Result {
        data class Success(val text: String) : Result()
        data class Error(val message: String) : Result()
    }

    companion object {
        private val loadedEngines = Collections.newSetFromMap(
            WeakHashMap<LocalAsrEngine, Boolean>()
        )
        private val engineLock = Any()

        fun releaseAll() = synchronized(engineLock) {
            loadedEngines.toList().forEach { it.release() }
        }
    }
}
