package me.trion.whispertype.ime

data class EmojiReplacement(
    val deleteBeforeCursor: Int,
    val commitText: String,
)

data class EmojiCandidate(
    val item: EmojiItem,
    val replacement: EmojiReplacement,
)

class EmojiSuggestionEngine(
    private val search: EmojiSearchEngine,
) {
    fun suggest(
        textBeforeCursor: CharSequence,
        limit: Int = MAX_CANDIDATES,
    ): List<EmojiCandidate> {
        if (limit <= 0) return emptyList()

        val match = TRIGGER_AT_CURSOR.find(textBeforeCursor) ?: return emptyList()
        if (match.range.last != textBeforeCursor.length - 1) return emptyList()

        val word = match.groupValues[1]
        val trailingSpace = match.groupValues[2]
        return search.suggestExact(word, minOf(limit, MAX_CANDIDATES)).map { item ->
            EmojiCandidate(
                item = item,
                replacement = EmojiReplacement(
                    deleteBeforeCursor = word.length + trailingSpace.length,
                    commitText = item.emoji + trailingSpace,
                ),
            )
        }
    }

    private companion object {
        const val MAX_CANDIDATES = 2
        val TRIGGER_AT_CURSOR = Regex("([\\p{L}]+)( ?)$")
    }
}
