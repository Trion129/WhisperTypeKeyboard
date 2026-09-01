package me.trion.whispertype.voice

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Shared import pipeline: extract the zip into a staging dir, find the three
 * sherpa Whisper files, canonicalize their names, verify, then swap them into
 * the target model dir via safeInstall. Never touches any model dir other than
 * the target, so a failed import leaves every other installed size intact.
 * The caller is responsible for setting the active model id on success.
 */
internal fun installImportZip(
    zipFile: File,
    modelsDir: File,
): ModelDownloader.Result {
    val staging = File(modelsDir, "import.staging")
    try {
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            ModelDownloader.extractZipFlat(zipFile, staging)
        } catch (e: Exception) {
            return ModelDownloader.Result.Error(ModelDownloader.MSG_IMPORT_READ)
        }

        val found = ModelDownloader.findModelFiles(staging)
        if (found == null) {
            return ModelDownloader.Result.Error(ModelDownloader.MSG_IMPORT_INVALID)
        }
        val (encoder, decoder, tokens) = found

        // If the package uses a catalog prefix (<id>-), it installs into that
        // model id's folder; anything else goes to the generic import slot.
        val targetId = ModelDownloader.detectCatalogId(encoder.name) ?: ModelCatalog.IMPORT_ID
        val names = ModelDownloader.requiredFileNames(targetId)
        val canonEncoder = File(staging, names[0])
        val canonDecoder = File(staging, names[1])
        val canonTokens = File(staging, names[2])

        fun canonicalize(src: File, dest: File) {
            if (src == dest) return
            dest.delete()
            src.copyTo(dest)
            src.delete()
        }
        canonicalize(encoder, canonEncoder)
        canonicalize(decoder, canonDecoder)
        canonicalize(tokens, canonTokens)

        // Drop anything that is not one of the three canonical files.
        staging.listFiles()?.forEach { f ->
            if (f.name != names[0] && f.name != names[1] && f.name != names[2]) f.delete()
        }

        return ModelDownloader.safeInstall(staging, File(modelsDir, targetId), names).fold(
            onSuccess = { ModelDownloader.Result.Success(targetId) },
            onFailure = { ModelDownloader.Result.Error(ModelDownloader.MSG_IMPORT_IO) }
        )
    } finally {
        if (staging.exists()) staging.deleteRecursively()
        zipFile.delete()
    }
}

class ModelDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun modelsDir(): File = File(context.filesDir, "models").also { it.mkdirs() }

    fun modelDir(id: String): File = File(modelsDir(), id)

    /** True when the model dir holds all three required files, non-empty. */
    fun isInstalled(id: String): Boolean = isInstalledDir(modelsDir(), id)

    /**
     * Returns (encoder, decoder, tokens) for the given id, or null when any
     * required file is missing or empty.
     */
    fun resolvePaths(id: String): Triple<File, File, File>? = resolvePathsIn(modelsDir(), id)

    /** Deletes the legacy RTranslator install folder (whisper_small_int8). */
    fun purgeLegacyInstall() {
        purgeLegacyDir(modelsDir())
    }

    /**
     * Downloads the three files of the catalog model [id] into
     * models/<id>/ with their final names. Progress reports the sum of bytes
     * across all files against the sum of known content lengths. A partial or
     * failed download is never counted as installed (all three files are
     * required), and other model dirs are never touched.
     */
    suspend fun download(id: String, onProgress: (done: Long, total: Long) -> Unit): Result =
        withContext(Dispatchers.IO) {
            val spec = ModelCatalog.byId(id)
            if (spec == null) return@withContext Result.Error("Unknown model id: $id")
            try {
                if (isInstalled(id)) return@withContext Result.Success(id)

                val dir = modelDir(id)
                dir.mkdirs()

                val files = listOf(
                    spec.encoderFileName to spec.encoderUrl(),
                    spec.decoderFileName to spec.decoderUrl(),
                    spec.tokensFileName to spec.tokensUrl(),
                )

                // Sum content lengths up front so progress has a stable total.
                var total = 0L
                for ((_, url) in files) {
                    try {
                        val head = Request.Builder().url(url).head().build()
                        client.newCall(head).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val len = resp.body?.contentLength() ?: -1L
                                if (len > 0) total += len
                            }
                        }
                    } catch (e: Exception) {
                        // Totals stay partial; progress falls back to bytes done.
                    }
                }

                var done = 0L
                for ((name, url) in files) {
                    val part = File(dir, "$name.part")
                    part.delete()
                    val request = Request.Builder().url(url).get().build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            part.delete()
                            return@withContext Result.Error(
                                "Download failed: HTTP ${response.code} ($name)"
                            )
                        }
                        val body = response.body
                            ?: return@withContext Result.Error("Empty response ($name)")
                        body.byteStream().use { input ->
                            FileOutputStream(part).use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                    done += read
                                    onProgress(done, total)
                                }
                            }
                        }
                    }
                    if (!part.renameTo(File(dir, name))) {
                        part.delete()
                        return@withContext Result.Error("Failed to write model file: $name")
                    }
                }

                if (resolvePaths(id) == null) {
                    return@withContext Result.Error("Model files incomplete after download")
                }
                Result.Success(id)
            } catch (e: Exception) {
                modelDir(id).listFiles()?.forEach { f ->
                    if (f.name.endsWith(".part")) f.delete()
                }
                Result.Error(e.message ?: "Download failed")
            }
        }

    /**
     * Copies a sherpa Whisper zip from [uri] into staging, validates the three
     * required files, installs them into the detected catalog id folder or the
     * import slot, and returns Success with the installed id.
     */
    suspend fun importFromUri(uri: Uri, onProgress: (copied: Long, total: Long) -> Unit): Result =
        withContext(Dispatchers.IO) {
            val partFile = File(modelsDir(), "import.zip.part")
            try {
                val resolver = context.contentResolver
                val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                val input = try {
                    resolver.openInputStream(uri)
                        ?: return@withContext Result.Error(MSG_IMPORT_READ)
                } catch (e: Exception) {
                    partFile.delete()
                    return@withContext Result.Error(MSG_IMPORT_READ)
                }
                try {
                    input.use { stream ->
                        FileOutputStream(partFile).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                copied += read
                                onProgress(copied, total)
                            }
                        }
                    }
                } catch (e: IOException) {
                    partFile.delete()
                    return@withContext Result.Error(MSG_IMPORT_IO)
                }
                installImportZip(partFile, modelsDir())
            } catch (e: Exception) {
                partFile.delete()
                Result.Error(MSG_IMPORT_READ)
            }
        }

    /** Deletes the install for [id] (downloads and imports alike). */
    fun delete(id: String) {
        modelDir(id).deleteRecursively()
    }

    sealed class Result {
        data class Success(val modelId: String) : Result()
        data class Error(val message: String) : Result()
    }

    companion object {
        const val MSG_IMPORT_READ = "Could not read the selected file"
        const val MSG_IMPORT_INVALID = "Invalid sherpa Whisper package (missing files)"
        const val MSG_IMPORT_IO = "Not enough space or write failed"

        val ENCODER_SUFFIXES = listOf("encoder.int8.onnx", "encoder.onnx")
        val DECODER_SUFFIXES = listOf("decoder.int8.onnx", "decoder.onnx")
        val TOKENS_SUFFIXES = listOf("tokens.txt")

        /**
         * Canonical file basenames inside a model dir for the given id.
         * Catalog ids use <id>-encoder.int8.onnx etc.; the import slot uses
         * encoder.int8.onnx etc.
         */
        fun requiredFileNames(id: String): List<String> = when (id) {
            ModelCatalog.IMPORT_ID -> listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")
            else -> {
                val spec = ModelCatalog.byId(id) ?: return emptyList()
                listOf(spec.encoderFileName, spec.decoderFileName, spec.tokensFileName)
            }
        }

        fun isInstalledDir(dir: File, id: String): Boolean = resolvePathsIn(dir, id) != null

        fun resolvePathsIn(dir: File, id: String): Triple<File, File, File>? {
            val modelDir = File(dir, id)
            if (id == ModelCatalog.IMPORT_ID) {
                val encoder = findFirst(modelDir, ENCODER_SUFFIXES) ?: return null
                val decoder = findFirst(modelDir, DECODER_SUFFIXES) ?: return null
                val tokens = findFirst(modelDir, TOKENS_SUFFIXES) ?: return null
                return Triple(encoder, decoder, tokens)
            }
            val spec = ModelCatalog.byId(id) ?: return null
            val encoder = File(modelDir, spec.encoderFileName)
            val decoder = File(modelDir, spec.decoderFileName)
            val tokens = File(modelDir, spec.tokensFileName)
            if (!encoder.isFile || encoder.length() == 0L) return null
            if (!decoder.isFile || decoder.length() == 0L) return null
            if (!tokens.isFile || tokens.length() == 0L) return null
            return Triple(encoder, decoder, tokens)
        }

        fun purgeLegacyDir(modelsDir: File) {
            File(modelsDir, ModelCatalog.LEGACY_FOLDER).deleteRecursively()
        }

        /**
         * Detects a catalog id from an imported encoder basename prefix
         * (<id>-), or null for unknown packages.
         */
        fun detectCatalogId(encoderName: String): String? =
            ModelCatalog.entries.firstOrNull { encoderName.startsWith("${it.id}-") }?.id

        /**
         * Locates the three logical files in a flat extracted dir.
         * Prefers int8 encoder/decoder names; falls back to plain .onnx.
         * Returns null when any file is missing or empty.
         */
        fun findModelFiles(dir: File): Triple<File, File, File>? {
            val encoder = findFirst(dir, ENCODER_SUFFIXES) ?: return null
            val decoder = findFirst(dir, DECODER_SUFFIXES) ?: return null
            val tokens = findFirst(dir, TOKENS_SUFFIXES) ?: return null
            return Triple(encoder, decoder, tokens)
        }

        fun findFirst(dir: File, suffixes: List<String>): File? {
            if (!dir.isDirectory) return null
            for (suffix in suffixes) {
                val match = dir.listFiles()?.firstOrNull {
                    it.isFile && it.name.endsWith(suffix) && it.length() > 0L
                }
                if (match != null) return match
            }
            return null
        }

        fun extractZipFlat(zipFile: File, destDir: File) {
            val seenBasenames = mutableSetOf<String>()
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry!!.isDirectory) {
                        val basename = File(entry!!.name).name
                        if (basename.isBlank()) {
                            entry = zis.nextEntry
                            continue
                        }
                        if (!seenBasenames.add(basename)) {
                            throw IllegalArgumentException(
                                "Duplicate required basename in archive: $basename"
                            )
                        }
                        val outFile = File(destDir, basename)
                        FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                    }
                    entry = zis.nextEntry
                }
            }
        }

        fun verifyRequiredFiles(dir: File, requiredNames: List<String>): List<String> {
            return requiredNames.filter { name ->
                val f = File(dir, name)
                !f.isFile || f.length() == 0L
            }
        }

        /**
         * Atomic-ish swap: verifies [requiredNames] in [stagingDir], backs up
         * the existing target dir, then renames staging into place. On any
         * failure the previous target is restored.
         */
        fun safeInstall(
            stagingDir: File,
            targetDir: File,
            requiredNames: List<String>,
        ): kotlin.Result<Unit> {
            val backupDir = File(targetDir.parentFile, targetDir.name + ".backup")
            var targetBackedUp = false
            try {
                if (!stagingDir.isDirectory) {
                    return kotlin.Result.failure(IllegalArgumentException("Staging dir is not a directory: $stagingDir"))
                }
                val missing = verifyRequiredFiles(stagingDir, requiredNames)
                if (missing.isNotEmpty()) {
                    return kotlin.Result.failure(
                        IllegalArgumentException("Staging dir missing required files: $missing")
                    )
                }
                backupDir.deleteRecursively()
                if (targetDir.exists()) {
                    if (!targetDir.renameTo(backupDir)) {
                        return kotlin.Result.failure(
                            IllegalStateException("Failed to back up existing model: $targetDir")
                        )
                    }
                    targetBackedUp = true
                }
                if (!stagingDir.renameTo(targetDir)) {
                    if (targetBackedUp && !backupDir.renameTo(targetDir)) {
                        backupDir.copyRecursively(targetDir, overwrite = true)
                        backupDir.deleteRecursively()
                    }
                    return kotlin.Result.failure(
                        IllegalStateException("Failed to rename staging to target: $stagingDir -> $targetDir")
                    )
                }
                backupDir.deleteRecursively()
                return kotlin.Result.success(Unit)
            } catch (e: Exception) {
                if (targetBackedUp && !targetDir.exists()) backupDir.renameTo(targetDir)
                return kotlin.Result.failure(e)
            } finally {
                if (stagingDir.exists()) {
                    stagingDir.deleteRecursively()
                }
            }
        }
    }
}
