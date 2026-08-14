package me.trion.whispertype.ime

class SuggestionEngine(private val wordlist: List<String>) {
    private val rank = wordlist.withIndex().associate { it.value.lowercase() to it.index }

    fun suggest(prefix: String, learned: Map<String, Int>, limit: Int = 3): List<String> {
        if (prefix.isBlank() || limit <= 0) return emptyList()
        val needle = prefix.lowercase()
        val candidates = linkedSetOf<String>()
        for (word in wordlist) {
            if (word.lowercase().startsWith(needle)) candidates.add(word.lowercase())
        }
        for (word in learned.keys) {
            if (word.startsWith(needle)) candidates.add(word)
        }
        val sorted = candidates.sortedWith(
            compareByDescending<String> { learned[it] ?: 0 }
                .thenBy { rank[it] ?: Int.MAX_VALUE }
                .thenBy { it }
        )
        val keepCase = prefix.any { it.isUpperCase() }
        val allCaps = keepCase && prefix.length > 1 && prefix.all { it.isUpperCase() }
        return sorted.take(limit).map { word ->
            when {
                !keepCase -> word
                allCaps -> word.uppercase()
                else -> word.replaceFirstChar { it.uppercaseChar() }
            }
        }
    }
}
