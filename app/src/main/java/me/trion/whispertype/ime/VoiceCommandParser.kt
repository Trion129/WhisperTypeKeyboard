package me.trion.whispertype.ime

/** Recognizes command phrases only at the end of a transcription. */
object VoiceCommandParser {
    enum class Command { CLEAR_ALL, CLEAR_LAST_WORD }

    data class Match(
        val command: Command,
        val dictationBeforeCommand: String,
        val wordCount: Int = 1,
    )

    fun parse(transcription: String, prefix: String): Match? {
        val words = prefix.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null

        val prefixPattern = words.joinToString("\\s+") { Regex.escape(it) }
        val pattern = Regex(
            "(?:^|(?<=\\s))$prefixPattern[,.:;!?-]*\\s+" +
                "(?:(clear\\s+all)|(clear\\s+last\\s+word)|(clear(?:[\\s,.:;!?-]+clear)*))[.!?,;:-]*$",
            RegexOption.IGNORE_CASE,
        )
        val trimmed = transcription.trim()
        val match = pattern.find(trimmed) ?: return null
        val command = if (match.groupValues[1].isNotEmpty()) {
            Command.CLEAR_ALL
        } else {
            Command.CLEAR_LAST_WORD
        }
        val wordCount = Regex("clear", RegexOption.IGNORE_CASE)
            .findAll(match.groupValues[3]).count().coerceAtLeast(1)
        val dictation = trimmed.substring(0, match.range.first).trimEnd()
        return Match(command, dictation, wordCount)
    }
}
