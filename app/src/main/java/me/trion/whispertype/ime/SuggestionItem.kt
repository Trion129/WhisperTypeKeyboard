package me.trion.whispertype.ime

sealed interface SuggestionItem {
    data class Word(val text: String) : SuggestionItem

    data class Emoji(val candidate: EmojiCandidate) : SuggestionItem
}

object SuggestionComposer {
    fun compose(
        words: List<String>,
        emojis: List<EmojiCandidate>,
        limit: Int = 3,
    ): List<SuggestionItem> {
        if (limit <= 0) return emptyList()

        val emojiItems = emojis
            .take(minOf(MAX_EMOJI_ITEMS, limit))
            .map(SuggestionItem::Emoji)
        val wordItems = words
            .take(limit - emojiItems.size)
            .map(SuggestionItem::Word)
        return emojiItems + wordItems
    }

    private const val MAX_EMOJI_ITEMS = 2
}
