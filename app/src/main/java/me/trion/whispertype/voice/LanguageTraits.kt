package me.trion.whispertype.voice

/**
 * Languages whose scripts need more tokens than sherpa's six-per-second
 * decoder budget, so a part must start padded to the full decode window.
 *
 * Provisional list from the dense-script plan (section 8.1). The bench
 * refines it; treat it as a hint, not a hard rule.
 */
private val DENSE_LANGUAGES = setOf(
    "hi", "bn", "ta", "te", "ml", "kn", "gu", "pa", "si", "mr", "ne", "as", "ur",
    "zh", "ja", "ko", "th", "lo", "my", "km",
    "ar", "fa", "he",
)

internal fun isDenseLanguage(code: String): Boolean =
    code.trim().lowercase() in DENSE_LANGUAGES
