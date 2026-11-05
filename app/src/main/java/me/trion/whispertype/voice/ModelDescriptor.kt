package me.trion.whispertype.voice

import java.io.File

sealed class ModelLayout {
    abstract val requiredPaths: List<String>

    data class Whisper(
        val encoder: String,
        val decoder: String,
        val tokens: String,
    ) : ModelLayout() {
        override val requiredPaths: List<String> = listOf(encoder, decoder, tokens)
    }

    data class NemoCtc(
        val model: String,
        val tokens: String,
    ) : ModelLayout() {
        override val requiredPaths: List<String> = listOf(model, tokens)
    }

    data class Canary(
        val encoder: String,
        val decoder: String,
        val tokens: String,
    ) : ModelLayout() {
        override val requiredPaths: List<String> = listOf(encoder, decoder, tokens)
    }
}

sealed class LanguagePolicy {
    abstract fun effectiveLanguage(requested: String): String

    data class Selectable(
        val codes: List<String>,
        val defaultCode: String = "",
    ) : LanguagePolicy() {
        override fun effectiveLanguage(requested: String): String =
            if (requested in codes) requested else defaultCode
    }

    data class Fixed(val code: String, val label: String) : LanguagePolicy() {
        override fun effectiveLanguage(requested: String): String = code
    }

    data class Automatic(val label: String) : LanguagePolicy() {
        override fun effectiveLanguage(requested: String): String = ""
    }
}

data class ModelArtifact(
    val path: String,
    val url: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
)

data class ModelSpec(
    val id: String,
    val title: String,
    val approxSizeMb: Int,
    val layout: ModelLayout,
    val languagePolicy: LanguagePolicy,
    val artifacts: List<ModelArtifact>,
    val description: String = "",
    val sourceUrl: String = "",
    val license: String = "",
    val revision: String = "",
    val maxSegmentMs: Long = defaultMaxSegmentMs(layout),
) {
    init {
        validateMaxSegmentMs(maxSegmentMs, layout)
    }
}

const val MIN_SEGMENT_MS = 5_000L
const val MAX_SEGMENT_MS = 60_000L

fun defaultMaxSegmentMs(layout: ModelLayout): Long = when (layout) {
    is ModelLayout.Whisper -> 28_000L
    // CTC has no Whisper frame cap; 45 seconds bounds decoder memory and latency.
    is ModelLayout.NemoCtc -> 45_000L
    // Non-autoregressive Canary has no frame cap either; the bench decoded 27.6 s in one pass.
    is ModelLayout.Canary -> 45_000L
}

fun validateMaxSegmentMs(value: Long, layout: ModelLayout): Long {
    require(value in MIN_SEGMENT_MS..MAX_SEGMENT_MS) { "maxSegmentMs must be between $MIN_SEGMENT_MS and $MAX_SEGMENT_MS" }
    if (layout is ModelLayout.Whisper) require(value <= 28_000L) {
        "Whisper maxSegmentMs must not exceed 28000"
    }
    return value
}

data class ResolvedModel(
    val spec: ModelSpec,
    val directory: File,
    val generation: String,
) {
    fun file(path: String): File = File(directory, path)
}

interface AsrEngine {
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String
    fun release()
}

internal fun formatTranscript(text: String): String {
    val trimmed = text.trim()
    return if (trimmed.length >= 2 && trimmed[0].isLowerCase()) {
        trimmed.replaceFirstChar { it.uppercaseChar() }
    } else {
        trimmed
    }
}
