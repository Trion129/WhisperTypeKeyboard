package me.trion.whispertype.voice

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The data-only descriptor stored beside an installed model package. */
data class InstalledModelManifest(val spec: ModelSpec, val generation: String)

/** Reads and writes the versioned, data-only model package descriptor. */
object ModelManifest {
    const val FILE_NAME = "model.json"
    const val VERSION = 1

    /** Throws [IllegalArgumentException] with a user-actionable reason for invalid manifests. */
    fun read(directory: File): InstalledModelManifest {
        val file = File(directory, FILE_NAME)
        require(file.isFile) { "Model manifest is missing" }
        val json = try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalArgumentException("Model manifest is invalid JSON", e)
        }
        val version = json.optInt("version", -1)
        require(version == VERSION) { "Unsupported model manifest version: $version" }
        val backend = json.requiredString("backend")
        require(backend == "whisper" || backend == "nemo_ctc" || backend == "canary") {
            "Unsupported model backend: $backend"
        }
        val files = json.optJSONObject("files")
            ?: throw IllegalArgumentException("Model manifest files mapping is missing")
        val expectedRoles = when (backend) {
            "whisper" -> setOf("encoder", "decoder", "tokens")
            "nemo_ctc" -> setOf("model", "tokens")
            "canary" -> setOf("encoder", "decoder", "tokens")
            else -> throw IllegalArgumentException("Unsupported model backend: $backend")
        }
        require(files.keys().asSequence().toSet() == expectedRoles) {
            "Model manifest roles do not match backend '$backend'"
        }
        val layout = when (backend) {
            "whisper" -> ModelLayout.Whisper(
                files.requiredString("encoder"), files.requiredString("decoder"), files.requiredString("tokens"),
            )
            "nemo_ctc" -> ModelLayout.NemoCtc(
                files.requiredString("model"), files.requiredString("tokens"),
            )
            "canary" -> ModelLayout.Canary(
                files.requiredString("encoder"), files.requiredString("decoder"), files.requiredString("tokens"),
            )
            else -> throw IllegalArgumentException("Unsupported model backend: $backend")
        }
        val language = json.optJSONObject("language")
            ?: throw IllegalArgumentException("Model manifest language policy is missing")
        val policy = when (language.requiredString("mode")) {
            "selectable" -> {
                val codes = language.optJSONArray("codes").strings("language codes", allowEmpty = true)
                val defaultCode = language.optionalString("defaultCode", "")
                require(codes.isNotEmpty() && codes.distinct().size == codes.size && defaultCode in codes) {
                    "Invalid selectable language policy"
                }
                LanguagePolicy.Selectable(codes, defaultCode)
            }
            "fixed" -> LanguagePolicy.Fixed(
                language.requiredString("code"), language.requiredString("label"),
            )
            "automatic" -> LanguagePolicy.Automatic(language.requiredString("label"))
            else -> throw IllegalArgumentException("Unsupported language policy")
        }
        require(layout !is ModelLayout.NemoCtc || policy !is LanguagePolicy.Selectable) {
            "NeMo CTC models do not support forced language selection"
        }
        val artifactPaths = json.optJSONArray("artifacts").strings("artifacts")
        require(artifactPaths.isNotEmpty()) { "Model manifest artifacts are empty" }
        val title = json.requiredString("title")
        val id = json.optionalString("id", ModelCatalog.IMPORT_ID)
        val artifacts = artifactPaths.map { path -> ModelArtifact(path = path, url = "") }
        val spec = ModelSpec(
            id = id,
            title = title,
            approxSizeMb = json.optInt("approxSizeMb", 0),
            layout = layout,
            languagePolicy = policy,
            artifacts = artifacts,
            description = json.optString("description", ""),
            sourceUrl = json.optString("sourceUrl", ""),
            license = json.optString("license", ""),
            revision = json.optString("revision", ""),
            maxSegmentMs = validateMaxSegmentMs(
                if (json.has("maxSegmentMs")) json.optLong("maxSegmentMs", -1L)
                else defaultMaxSegmentMs(layout),
                layout,
            ),
        )
        // Import archives describe a package and never supply a trusted install generation.
        val generation = json.optString("generation", "")
        return InstalledModelManifest(spec, generation)
    }

    fun write(directory: File, spec: ModelSpec, generation: String) {
        val json = JSONObject()
            .put("version", VERSION)
            .put("id", spec.id)
            .put("generation", generation)
            .put("title", spec.title)
            .put("backend", when (spec.layout) {
                is ModelLayout.Whisper -> "whisper"
                is ModelLayout.NemoCtc -> "nemo_ctc"
                is ModelLayout.Canary -> "canary"
            })
            .put("files", JSONObject().apply {
                when (val layout = spec.layout) {
                    is ModelLayout.Whisper -> put("encoder", layout.encoder).put("decoder", layout.decoder)
                        .put("tokens", layout.tokens)
                    is ModelLayout.NemoCtc -> put("model", layout.model).put("tokens", layout.tokens)
                    is ModelLayout.Canary -> put("encoder", layout.encoder).put("decoder", layout.decoder)
                        .put("tokens", layout.tokens)
                }
            })
            .put("artifacts", JSONArray().apply { spec.artifacts.forEach { put(it.path) } })
            .put("language", JSONObject().apply {
                when (val policy = spec.languagePolicy) {
                    is LanguagePolicy.Selectable -> put("mode", "selectable")
                        .put("codes", JSONArray(policy.codes)).put("defaultCode", policy.defaultCode)
                    is LanguagePolicy.Fixed -> put("mode", "fixed").put("code", policy.code).put("label", policy.label)
                    is LanguagePolicy.Automatic -> put("mode", "automatic").put("label", policy.label)
                }
            })
            .put("approxSizeMb", spec.approxSizeMb)
            .put("description", spec.description)
            .put("sourceUrl", spec.sourceUrl)
            .put("license", spec.license)
            .put("revision", spec.revision)
            .put("maxSegmentMs", spec.maxSegmentMs)
        File(directory, FILE_NAME).writeText(json.toString(2), Charsets.UTF_8)
    }

    private fun JSONObject.requiredString(key: String): String = optionalString(key, "").also {
        require(it.isNotBlank()) { "Model manifest field '$key' is missing" }
    }

    private fun JSONObject.optionalString(key: String, default: String): String {
        if (!has(key)) return default
        val value = opt(key)
        require(value is String) { "Model manifest field '$key' must be a string" }
        return value
    }

    private fun JSONArray?.strings(field: String, allowEmpty: Boolean = false): List<String> {
        require(this != null) { "Model manifest $field are missing" }
        return List(length()) { index ->
            val value = opt(index)
            require(value is String && (allowEmpty || value.isNotBlank())) {
                "Model manifest $field contain an invalid string"
            }
            value as String
        }
    }
}
