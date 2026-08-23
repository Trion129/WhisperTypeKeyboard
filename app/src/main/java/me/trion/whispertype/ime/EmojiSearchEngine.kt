package me.trion.whispertype.ime

class EmojiSearchEngine(
    private val catalog: EmojiCatalog,
    private val aliases: Map<String, List<String>> = DEFAULT_ALIASES,
) {
    private val indexedItems = catalog.items.mapIndexed { index, item ->
        val normalizedName = normalize(item.name)
        val normalizedKeywords = item.keywords.map(::normalize).filter(String::isNotEmpty)
        IndexedItem(
            index = index,
            item = item,
            fields = (
                listOf(normalizedName, normalize(item.group), normalize(item.subgroup)) +
                    normalizedKeywords
                ).filter(String::isNotEmpty),
            exactSuggestionTerms = (listOf(normalizedName) + normalizedKeywords).filter(String::isNotEmpty),
        )
    }

    fun search(query: String): List<EmojiItem> {
        val raw = query.trim()
        if (raw.isEmpty()) return emptyList()
        catalog.items.firstOrNull { it.emoji == raw }?.let { return listOf(it) }

        val needle = normalize(raw)
        if (needle.isEmpty()) return emptyList()
        val aliasOrder = aliases[needle].orEmpty()

        return indexedItems.mapNotNull { indexed ->
            val aliasScore = aliasOrder.indexOf(indexed.item.emoji)
            val score = when {
                aliasScore >= 0 -> aliasScore
                indexed.fields.any { it == needle } -> EXACT_FIELD_SCORE
                indexed.fields.any { it.containsWholePhrase(needle) } -> WHOLE_PHRASE_SCORE
                indexed.fields.any { it.hasTokenPrefix(needle) } -> PREFIX_SCORE
                indexed.fields.any { it.contains(needle) } -> SUBSTRING_SCORE
                else -> return@mapNotNull null
            }
            ScoredItem(score, indexed.index, indexed.item)
        }.sortedWith(compareBy<ScoredItem> { it.score }.thenBy { it.index })
            .map { it.item }
    }

    fun suggestExact(trigger: String, limit: Int = 2): List<EmojiItem> {
        if (limit <= 0) return emptyList()
        val needle = normalize(trigger)
        if (needle.isEmpty()) return emptyList()
        val isExact = aliases.containsKey(needle) ||
            indexedItems.any { needle in it.exactSuggestionTerms }
        return if (isExact) search(needle).take(limit) else emptyList()
    }

    private fun String.containsWholePhrase(needle: String): Boolean {
        var start = indexOf(needle)
        while (start >= 0) {
            val end = start + needle.length
            if ((start == 0 || this[start - 1] == ' ') && (end == length || this[end] == ' ')) return true
            start = indexOf(needle, start + 1)
        }
        return false
    }

    private fun String.hasTokenPrefix(needle: String): Boolean {
        var start = 0
        while (start < length) {
            if (regionMatches(start, needle, 0, needle.length)) return true
            start = indexOf(' ', start)
            if (start < 0) return false
            start++
        }
        return false
    }

    private data class IndexedItem(
        val index: Int,
        val item: EmojiItem,
        val fields: List<String>,
        val exactSuggestionTerms: List<String>,
    )

    private data class ScoredItem(
        val score: Int,
        val index: Int,
        val item: EmojiItem,
    )

    companion object {
        private const val EXACT_FIELD_SCORE = 100
        private const val WHOLE_PHRASE_SCORE = 200
        private const val PREFIX_SCORE = 300
        private const val SUBSTRING_SCORE = 400
        private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
        private val REPEATED_WHITESPACE = Regex("\\s+")

        val DEFAULT_ALIASES: Map<String, List<String>> = linkedMapOf(
            "haha" to listOf("😂", "🤣"),
            "lol" to listOf("😂", "🤣"),
            "laugh" to listOf("😂", "🤣"),
            "cry" to listOf("😢", "😭"),
            "sad" to listOf("😢", "😭"),
            "love" to listOf("❤️", "😍"),
            "heart" to listOf("❤️", "😍"),
        )

        private fun normalize(value: String): String = value.lowercase()
            .replace(NON_ALPHANUMERIC, " ")
            .trim()
            .replace(REPEATED_WHITESPACE, " ")
    }
}
