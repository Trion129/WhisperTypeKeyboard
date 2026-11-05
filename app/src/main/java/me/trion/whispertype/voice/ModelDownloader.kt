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
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class ModelDownloader(private val context: Context) {

    fun modelsDir(): File = File(context.filesDir, "models").also { it.mkdirs() }
    fun modelDir(id: String): File {
        require(id == ModelCatalog.IMPORT_ID || ModelCatalog.byId(id) != null) { "Unknown model ID: $id" }
        return File(modelsDir(), modelDirectoryName(id))
    }
    fun isInstalled(id: String): Boolean = resolveModel(id) != null
    fun resolveModel(id: String): ResolvedModel? = resolveModelIn(modelsDir(), id)
    fun modelSpec(id: String): ModelSpec? = resolveModel(id)?.spec ?: ModelCatalog.byId(id)
    private fun modelDirectoryName(id: String): String = directoryNameForModel(id)

    /** Deletes the legacy RTranslator install folder (whisper_small_int8). */
    fun purgeLegacyInstall() = purgeLegacyDir(modelsDir())

    suspend fun download(id: String, onProgress: (done: Long, total: Long) -> Unit): Result =
        withContext(Dispatchers.IO) {
            val spec = ModelCatalog.byId(id)
                ?: return@withContext Result.Error("Unknown model id: $id")
            try {
                if (resolveModel(id) != null) return@withContext Result.Success(id)
                validateSpec(spec)?.let { return@withContext Result.Error(it) }
                val root = modelsDir()
                val staging = File(root, ".${id}.staging-${UUID.randomUUID()}")
                staging.mkdirs()
                try {
                    fetchArtifacts(spec, staging, onProgress)
                    val missing = verifyPackage(staging, spec)
                    if (missing != null) return@withContext Result.Error(missing)
                    val generation = newGeneration()
                    ModelManifest.write(staging, spec, generation)
                    val installed = LocalAsrEngine.withModelMutation {
                        safeInstall(staging, modelDir(id), spec.layout.requiredPaths + ModelManifest.FILE_NAME)
                    }
                    installed.fold(
                        onSuccess = { Result.Success(id) },
                        onFailure = { Result.Error(it.message ?: MSG_INSTALL_IO) },
                    )
                } finally {
                    staging.deleteRecursively()
                }
            } catch (e: Exception) {
                Result.Error(e.message ?: "Download failed")
            }
        }

    suspend fun importFromUri(uri: Uri, onProgress: (copied: Long, total: Long) -> Unit): Result =
        withContext(Dispatchers.IO) {
            val partFile = File(modelsDir(), "import-${UUID.randomUUID()}.zip.part")
            try {
                val resolver = context.contentResolver
                val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                val input = try {
                    resolver.openInputStream(uri) ?: return@withContext Result.Error(MSG_IMPORT_READ)
                } catch (e: Exception) {
                    return@withContext Result.Error(MSG_IMPORT_READ)
                }
                input.use { stream ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            onProgress(copied, total)
                        }
                    }
                }
                installImportZip(partFile, modelsDir())
            } catch (e: Exception) {
                Result.Error(e.message ?: MSG_IMPORT_READ)
            } finally {
                partFile.delete()
            }
        }

    fun delete(id: String) {
        require(id == ModelCatalog.IMPORT_ID || ModelCatalog.byId(id) != null) { "Unknown model ID: $id" }
        val root = modelsDir()
        LocalAsrEngine.withModelMutation {
            val directory = File(root, modelDirectoryName(id))
            migrateLegacyCatalogDirectory(root, id, directory)
            directory.deleteRecursively()
        }
    }

    sealed class Result {
        data class Success(val modelId: String) : Result()
        data class Error(val message: String) : Result()
    }

    companion object {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
        const val MSG_IMPORT_READ = "Could not read the selected file"
        const val MSG_IMPORT_INVALID = "Invalid model package"
        const val MSG_IMPORT_IO = "Not enough space or write failed"
        const val MSG_INSTALL_IO = "Not enough space or model install failed"
        const val MULTILINGUAL_METADATA_KEY = "is_multilingual"
        private const val METADATA_TAIL_BYTES = 64 * 1024L
        private fun directoryNameForModel(id: String): String =
            if (id == ModelCatalog.IMPORT_ID) id else "downloaded-$id"
        private fun migrateLegacyCatalogDirectory(modelsDir: File, id: String, directory: File) {
            if (id == ModelCatalog.IMPORT_ID) return
            val legacyDirectory = File(modelsDir, id)
            if (!directory.exists() && legacyDirectory.isDirectory) legacyDirectory.renameTo(directory)
        }

        /** Shared catalog acquisition for Android installs and opt-in host replay. */
        fun fetchArtifacts(
            spec: ModelSpec,
            directory: File,
            onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        ) {
            validateSpec(spec)?.let { throw IllegalArgumentException(it) }
            var total = 0L
            var totalKnown = true
            for (artifact in spec.artifacts) {
                require(artifact.url.isNotBlank()) { "No download URL for ${artifact.path}" }
                val size = artifact.sizeBytes ?: runCatching {
                    client.newCall(Request.Builder().url(artifact.url).head().build()).execute().use {
                        if (it.isSuccessful) it.body?.contentLength()?.takeIf { length -> length > 0 } else null
                    }
                }.getOrNull()
                if (size == null) totalKnown = false else total += size
            }
            if (!totalKnown) total = -1L
            var done = 0L
            val buffer = ByteArray(64 * 1024)
            for (artifact in spec.artifacts) {
                val destination = containedFile(directory, artifact.path)
                if (destination.isFile && destination.length() > 0) {
                    verifyArtifact(destination, artifact)
                    done += destination.length()
                    onProgress(done, total)
                    continue
                }
                destination.parentFile?.mkdirs()
                val part = File(destination.parentFile, destination.name + ".part")
                try {
                    val digest = artifact.sha256?.let { MessageDigest.getInstance("SHA-256") }
                    val request = Request.Builder().url(artifact.url).get().build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException(
                            "Download failed: HTTP ${response.code} (${artifact.path})",
                        )
                        val body = response.body ?: throw IOException("Empty response (${artifact.path})")
                        body.byteStream().use { input ->
                            FileOutputStream(part).use { output ->
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    digest?.update(buffer, 0, count)
                                    done += count
                                    onProgress(done, total)
                                }
                            }
                        }
                    }
                    require(part.length() > 0) { "Empty downloaded artifact: ${artifact.path}" }
                    artifact.sizeBytes?.let {
                        require(part.length() == it) { "Downloaded size mismatch: ${artifact.path}" }
                    }
                    if (digest != null) {
                        val hash = digest.digest().joinToString("") { "%02x".format(it) }
                        require(hash.equals(artifact.sha256, ignoreCase = true)) {
                            "Downloaded SHA-256 mismatch: ${artifact.path}"
                        }
                    }
                    if (!part.renameTo(destination)) throw IOException("Failed to write model file: ${artifact.path}")
                } finally {
                    part.delete()
                }
            }
        }

        fun resolveModelIn(modelsDir: File, id: String): ResolvedModel? {
            if (id.isBlank() || '/' in id || '\\' in id || id == "." || id == "..") return null
            if (id != ModelCatalog.IMPORT_ID && ModelCatalog.byId(id) == null) return null
            val directory = File(modelsDir, directoryNameForModel(id))
            if (id != ModelCatalog.IMPORT_ID) {
                LocalAsrEngine.withModelMutation {
                    migrateLegacyCatalogDirectory(modelsDir, id, directory)
                }
            }
            if (!directory.isDirectory) return null
            val installedManifest = File(directory, ModelManifest.FILE_NAME)
            if (installedManifest.isFile) {
                val manifest = try { ModelManifest.read(directory) } catch (_: Exception) { return null }
                if (manifest.spec.id != id || validateSpec(manifest.spec) != null) return null
                if (verifyPackage(directory, manifest.spec) != null) return null
                val generation = manifest.generation.takeIf { it.isNotBlank() }
                    ?: legacyGeneration(directory, manifest.spec)
                return ResolvedModel(manifest.spec, directory, generation)
            }
            val catalog = ModelCatalog.byId(id)
            val spec = if (catalog != null) legacyCatalogSpec(directory, catalog)
                else legacyImportSpec(directory) ?: return null
            if (verifyPackage(directory, spec) != null) return null
            val generation = legacyGeneration(directory, spec)
            // Persist one descriptor without downloading or altering model weights.
            try {
                LocalAsrEngine.withModelMutation { ModelManifest.write(directory, spec, generation) }
            } catch (_: Exception) {
                return ResolvedModel(spec, directory, generation)
            }
            return ResolvedModel(spec, directory, generation)
        }

        fun resolveModelDirectory(directory: File, spec: ModelSpec): ResolvedModel? {
            if (!directory.isDirectory || validateSpec(spec) != null) return null
            val manifestFile = File(directory, ModelManifest.FILE_NAME)
            val manifest = if (manifestFile.isFile) {
                try { ModelManifest.read(directory) } catch (_: Exception) { return null }
            } else null
            val effectiveSpec = manifest?.spec ?: spec
            if (effectiveSpec.id != spec.id || verifyPackage(directory, effectiveSpec) != null) return null
            val generation = manifest?.generation?.takeIf { it.isNotBlank() }
                ?: legacyGeneration(directory, effectiveSpec)
            if (manifest == null) {
                try { LocalAsrEngine.withModelMutation { ModelManifest.write(directory, effectiveSpec, generation) } }
                catch (_: Exception) { /* host cache may be read-only */ }
            }
            return ResolvedModel(effectiveSpec, directory, generation)
        }

        /** Installs either a versioned descriptor package or a narrowly recognized legacy Whisper ZIP. */
        internal fun installImportZip(zipFile: File, modelsDir: File): Result {
            val staging = File(modelsDir, ".import.staging-${UUID.randomUUID()}")
            try {
                staging.mkdirs()
                extractZip(zipFile, staging)
                normalizePackageRoot(staging)
                val packageManifest = File(staging, ModelManifest.FILE_NAME)
                val packageSpec = if (packageManifest.isFile) {
                    val parsed = ModelManifest.read(staging).spec
                    parsed.copy(
                        id = ModelCatalog.IMPORT_ID,
                        artifacts = parsed.artifacts.map { it.copy(url = "") },
                    )
                } else {
                    legacyImportSpec(staging) ?: return Result.Error(MSG_IMPORT_INVALID)
                }
                validateSpec(packageSpec)?.let { return Result.Error(it) }
                verifyPackage(staging, packageSpec)?.let { return Result.Error(it) }
                val generation = newGeneration()
                ModelManifest.write(staging, packageSpec, generation)
                val installed = LocalAsrEngine.withModelMutation {
                    safeInstall(staging, File(modelsDir, packageSpec.id), packageSpec.layout.requiredPaths + ModelManifest.FILE_NAME)
                }
                return installed.fold(
                    onSuccess = { Result.Success(packageSpec.id) },
                    onFailure = { Result.Error(it.message ?: MSG_IMPORT_IO) },
                )
            } catch (e: IllegalArgumentException) {
                return Result.Error(e.message ?: MSG_IMPORT_INVALID)
            } catch (e: Exception) {
                return Result.Error(e.message ?: MSG_IMPORT_READ)
            } finally {
                staging.deleteRecursively()
                zipFile.delete()
            }
        }

        fun purgeLegacyDir(modelsDir: File) {
            LocalAsrEngine.withModelMutation { File(modelsDir, ModelCatalog.LEGACY_FOLDER).deleteRecursively() }
        }

        /** ONNX metadata key the sherpa Whisper export writes as "0"/"1". */
        fun isMultilingualOnnx(file: File): Boolean = readMetadataValue(file, MULTILINGUAL_METADATA_KEY) == "1"

        internal fun readMetadataValue(file: File, key: String): String? {
            if (!file.isFile || file.length() == 0L) return null
            val tailSize = minOf(file.length(), METADATA_TAIL_BYTES).toInt()
            val tail = ByteArray(tailSize)
            try {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(file.length() - tailSize)
                    raf.readFully(tail)
                }
            } catch (_: Exception) { return null }
            val needle = key.toByteArray(Charsets.US_ASCII)
            var searchFrom = 0
            while (searchFrom <= tail.size - needle.size) {
                val idx = indexOfBytes(tail, needle, searchFrom) ?: return null
                readLengthDelimitedString(tail, idx + needle.size)?.let { return it }
                searchFrom = idx + needle.size
            }
            return null
        }

        private fun legacyCatalogSpec(directory: File, catalog: ModelSpec): ModelSpec {
            val artifacts = directory.walkTopDown()
                .filter { it.isFile && it.name != ModelManifest.FILE_NAME && !it.name.endsWith(".part") }
                .map { ModelArtifact(relativePath(directory, it), "") }
                .toList()
            return catalog.copy(artifacts = artifacts)
        }

        private fun legacyImportSpec(directory: File): ModelSpec? {
            val files = directory.walkTopDown().filter { it.isFile && it.name != ModelManifest.FILE_NAME }.toList()
            val encoders = files.filter { it.name.endsWith("encoder.int8.onnx") || it.name.endsWith("encoder.onnx") }
            val decoders = files.filter { it.name.endsWith("decoder.int8.onnx") || it.name.endsWith("decoder.onnx") }
            val tokens = files.filter { it.name.endsWith("tokens.txt") }
            if (encoders.size != 1 || decoders.size != 1 || tokens.size != 1) return null
            val encoder = relativePath(directory, encoders.single())
            val decoder = relativePath(directory, decoders.single())
            val tokenPath = relativePath(directory, tokens.single())
            // Catalog prefixes retain useful display and language metadata, never catalog identity.
            val catalogId = ModelCatalog.entries.firstOrNull { encoder.substringAfterLast('/').startsWith("${it.id}-") }?.id
            if (catalogId != null) {
                val known = ModelCatalog.byId(catalogId) ?: return null
                return known.copy(
                    id = ModelCatalog.IMPORT_ID,
                    title = "Imported ${known.title}",
                    layout = ModelLayout.Whisper(encoder, decoder, tokenPath),
                    artifacts = files.map { ModelArtifact(relativePath(directory, it), "") },
                )
            }
            val multilingual = isMultilingualOnnx(encoders.single())
            val policy = if (multilingual) {
                LanguagePolicy.Selectable(ModelCatalog.languageOptions.map { it.code })
            } else LanguagePolicy.Fixed("en", "English")
            return ModelSpec(
                id = ModelCatalog.IMPORT_ID,
                title = "Imported model",
                approxSizeMb = 0,
                layout = ModelLayout.Whisper(encoder, decoder, tokenPath),
                languagePolicy = policy,
                artifacts = files.map { ModelArtifact(relativePath(directory, it), "") },
            )
        }


        private fun extractZip(zipFile: File, destination: File) {
            val seen = mutableSetOf<String>()
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.removeSuffix("/")
                    if (name.isNotEmpty() && isFinderMetadataEntry(name)) {
                        zip.closeEntry()
                        continue
                    }
                    if (name.isNotEmpty()) validateRelativePath(name)
                    if (!entry.isDirectory) {
                        require(seen.add(name)) { "Duplicate package path: $name" }
                        val out = containedFile(destination, name)
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
        }
        private fun isFinderMetadataEntry(path: String): Boolean {
            val segments = path.split('/')
            return "__MACOSX" in segments || segments.any { it.startsWith("._") || it == ".DS_Store" }
        }


        private fun normalizePackageRoot(directory: File) {
            if (File(directory, ModelManifest.FILE_NAME).isFile) return
            val manifests = directory.walkTopDown().filter { it.isFile && it.name == ModelManifest.FILE_NAME }.toList()
            if (manifests.isEmpty()) return
            require(manifests.size == 1) { "Model manifest is ambiguous" }
            val packageRoot = manifests.single().parentFile
            require(packageRoot != directory) { "Model package root is ambiguous" }
            val outside = directory.listFiles().orEmpty().filter { it != packageRoot && it != packageRoot.let { root ->
                var child = root
                while (child.parentFile != directory && child.parentFile != null) child = child.parentFile
                child
            } }
            require(outside.isEmpty()) { "Model package contains files outside its manifest root" }
            val files = packageRoot.walkTopDown().filter { it.isFile }.toList()
            for (source in files) {
                val relative = relativePath(packageRoot, source)
                val target = containedFile(directory, relative)
                require(!target.exists()) { "Duplicate package path: $relative" }
                target.parentFile?.mkdirs()
                if (!source.renameTo(target)) source.copyTo(target).also { source.delete() }
            }
            packageRoot.deleteRecursively()
        }

        private fun validateSpec(spec: ModelSpec): String? {
            if (spec.id.isBlank() || spec.title.isBlank()) return "Model descriptor is missing an ID or title"
            val artifacts = spec.artifacts.map { it.path }
            if (artifacts.isEmpty() || artifacts.distinct().size != artifacts.size) return "Model descriptor has missing or duplicate artifacts"
            if (ModelManifest.FILE_NAME in artifacts) return "Model artifact path is reserved: ${ModelManifest.FILE_NAME}"
            try { artifacts.forEach(::validateRelativePath) } catch (e: IllegalArgumentException) { return e.message }
            val required = spec.layout.requiredPaths
            if (required.distinct().size != required.size || required.any { it !in artifacts }) {
                return "Model descriptor artifacts do not include every required role"
            }
            when (val layout = spec.layout) {
                is ModelLayout.Whisper -> if (listOf(layout.encoder, layout.decoder, layout.tokens).any { it !in artifacts }) return "Whisper role paths do not match artifacts"
                is ModelLayout.NemoCtc -> if (listOf(layout.model, layout.tokens).any { it !in artifacts }) return "NeMo CTC role paths do not match artifacts"
                is ModelLayout.Canary -> if (listOf(layout.encoder, layout.decoder, layout.tokens).any { it !in artifacts }) return "Canary role paths do not match artifacts"
            }
            return null
        }

        private fun verifyPackage(directory: File, spec: ModelSpec): String? {
            validateSpec(spec)?.let { return it }
            val declared = spec.artifacts.map { it.path }.toSet()
            val required = spec.layout.requiredPaths
            for (path in required) {
                val file = try { containedFile(directory, path) } catch (e: Exception) { return e.message }
                if (!file.isFile || file.length() == 0L) return "Model package is missing required file: $path"
            }
            for (path in declared) {
                val file = containedFile(directory, path)
                if (!file.isFile || file.length() == 0L) return "Model package is missing declared artifact: $path"
            }
            val actual = directory.walkTopDown()
                .filter { it.isFile && it.name != ModelManifest.FILE_NAME && !it.name.endsWith(".part") }
                .map { relativePath(directory, it) }.toSet()
            val undeclared = actual - declared
            if (undeclared.isNotEmpty()) return "Model package contains undeclared files: ${undeclared.sorted()}"
            return null
        }

        private fun verifyArtifact(file: File, artifact: ModelArtifact) {
            artifact.sizeBytes?.let { require(file.length() == it) { "Downloaded size mismatch: ${artifact.path}" } }
            artifact.sha256?.let { expected ->
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(actual.equals(expected, ignoreCase = true)) { "Downloaded SHA-256 mismatch: ${artifact.path}" }
            }
        }

        private fun safeInstall(stagingDir: File, targetDir: File, requiredNames: List<String>): kotlin.Result<Unit> {
            val backupDir = File(targetDir.parentFile, targetDir.name + ".backup")
            var backedUp = false
            try {
                if (!stagingDir.isDirectory) return kotlin.Result.failure(IllegalArgumentException("Staging dir is not a directory"))
                val missing = verifyRequiredFiles(stagingDir, requiredNames)
                if (missing.isNotEmpty()) return kotlin.Result.failure(IllegalArgumentException("Staging dir missing required files: $missing"))
                backupDir.deleteRecursively()
                if (targetDir.exists()) {
                    if (!targetDir.renameTo(backupDir)) return kotlin.Result.failure(IllegalStateException("Failed to back up existing model"))
                    backedUp = true
                }
                if (!stagingDir.renameTo(targetDir)) {
                    if (backedUp && !backupDir.renameTo(targetDir)) {
                        backupDir.copyRecursively(targetDir, overwrite = true)
                        backupDir.deleteRecursively()
                    }
                    return kotlin.Result.failure(IllegalStateException("Failed to move staged model into place"))
                }
                backupDir.deleteRecursively()
                return kotlin.Result.success(Unit)
            } catch (e: Exception) {
                if (backedUp && !targetDir.exists()) backupDir.renameTo(targetDir)
                return kotlin.Result.failure(e)
            } finally {
                if (stagingDir.exists()) stagingDir.deleteRecursively()
            }
        }

        private fun verifyRequiredFiles(directory: File, paths: List<String>): List<String> = paths.filter {
            val file = try { containedFile(directory, it) } catch (_: Exception) { return@filter true }
            !file.isFile || file.length() == 0L
        }

        private fun validateRelativePath(path: String) {
            require(path.isNotBlank() && '\u0000' !in path && '\\' !in path && !path.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(path)) {
                "Unsafe model path: $path"
            }
            val parts = path.split('/')
            require(parts.all { it.isNotBlank() && it != "." && it != ".." }) { "Unsafe model path: $path" }
        }

        private fun containedFile(root: File, path: String): File {
            validateRelativePath(path)
            val base = root.canonicalFile
            val file = File(root, path).canonicalFile
            require(file.path.startsWith(base.path + File.separator)) { "Unsafe model path: $path" }
            return file
        }

        private fun relativePath(root: File, file: File): String =
            file.relativeTo(root).invariantSeparatorsPath

        private fun legacyGeneration(directory: File, spec: ModelSpec): String {
            val digest = MessageDigest.getInstance("SHA-256")
            spec.artifacts.forEach { artifact ->
                val file = containedFile(directory, artifact.path)
                digest.update(artifact.path.toByteArray(Charsets.UTF_8))
                digest.update(file.length().toString().toByteArray(Charsets.UTF_8))
                digest.update(file.lastModified().toString().toByteArray(Charsets.UTF_8))
            }
            return digest.digest().take(12).joinToString("") { "%02x".format(it) }
        }

        private fun newGeneration(): String = UUID.randomUUID().toString()

        private fun readLengthDelimitedString(buffer: ByteArray, pos: Int): String? {
            var p = pos
            if (p >= buffer.size || (buffer[p].toInt() and 0xFF) != 0x12) return null
            p++
            var length = 0
            var shift = 0
            while (p < buffer.size && shift <= 14) {
                val b = buffer[p++].toInt() and 0xFF
                length = length or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) {
                    if (length <= 0 || length > 64 || p + length > buffer.size) return null
                    return String(buffer, p, length, Charsets.UTF_8)
                }
                shift += 7
            }
            return null
        }

        private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, from: Int): Int? {
            for (i in from..haystack.size - needle.size) {
                var matches = true
                for (j in needle.indices) if (haystack[i + j] != needle[j]) { matches = false; break }
                if (matches) return i
            }
            return null
        }
    }
}
