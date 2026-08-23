package me.trion.whispertype.ime

class EmojiSearchSession(initialQuery: String = "") {
    var query: String = initialQuery
        private set

    fun append(text: String): String {
        if (text.all { it.isLetterOrDigit() || it == ' ' }) query += text
        return query
    }

    fun backspace(): String {
        if (query.isNotEmpty()) {
            query = query.substring(0, query.offsetByCodePoints(query.length, -1))
        }
        return query
    }

    fun clear(): String {
        query = ""
        return query
    }
}
