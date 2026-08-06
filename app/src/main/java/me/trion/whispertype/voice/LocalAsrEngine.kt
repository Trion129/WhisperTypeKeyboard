package me.trion.whispertype.voice

import android.content.Context
import android.util.Log
import me.trion.whispertype.util.Prefs
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

class LocalAsrEngine(private val context: Context) {
    private val downloader = ModelDownloader(context)
    private val prefs = Prefs(context)
    private var engine: SherpaWhisperEngine? = null
    private var loadedModelId: String? = null
    private var legacyMaintenanceDone = false

    /**
     * One-time upgrade path from 1.3.x: the old RTranslator folder is not
     * usable by sherpa, so it is purged and obsolete prefs are dropped.
     */
    private fun runLegacyMaintenance() {
        if (legacyMaintenanceDone) return
        legacyMaintenanceDone = true
        runCatching { downloader.purgeLegacyInstall() }
        prefs.migrate()
    }

    fun ensureLoaded(): String? = synchronized(engineLock) {
        runLegacyMaintenance()
        val id = prefs.activeModelId
        if (id.isBlank()) return "Install a model in settings"
        val paths = downloader.resolvePaths(id)
        if (paths == null) return "Model files are missing, reinstall in settings"
        if (engine != null && loadedModelId == id) return null
        if (engine != null) release()
        return try {
            val (encoder, decoder, tokens) = paths
            engine = SherpaWhisperEngine(
                encoderPath = encoder.absolutePath,
                decoderPath = decoder.absolutePath,
                tokensPath = tokens.absolutePath,
            )
            loadedModelId = id
            synchronized(loadedEngines) { loadedEngines.add(this) }
            null
        } catch (e: Exception) {
            Log.e("WhisperType", "Failed to load model", e)
            "Failed to load model: ${e.message}"
        }
    }

    fun isModelReady(): Boolean {
        runLegacyMaintenance()
        val id = prefs.activeModelId
        return id.isNotBlank() && downloader.isInstalled(id)
    }

    fun currentModelTitle(): String {
        val id = prefs.activeModelId
        return ModelCatalog.byId(id)?.title ?: "Imported model"
    }

    fun transcribeWav(wavFile: File): Result = synchronized(engineLock) {
        val loadError = ensureLoaded()
        if (loadError != null) return Result.Error(loadError)
        return try {
            val wav = WavReader.read(wavFile)
            val text = engine!!.transcribe(wav.samples, wav.sampleRate)
            if (text.isBlank()) Result.Error("No speech detected") else Result.Success(text)
        } catch (e: Exception) {
            Log.e("WhisperType", "Transcription failed", e)
            Result.Error(e.message ?: "Transcription failed")
        }
    }

    fun release() = synchronized(engineLock) {
        engine?.release()
        engine = null
        loadedModelId = null
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
