package me.trion.whispertype.ime

enum class FieldVariation { NORMAL, URI, EMAIL, PASSWORD, NUMBER }

object TypingRules {
    fun shouldAutoPeriod(textBeforeCursor: String, variation: FieldVariation): Boolean {
        if (variation != FieldVariation.NORMAL) return false
        if (textBeforeCursor.isEmpty()) return false
        val last = textBeforeCursor.last()
        return last.isLetterOrDigit()
    }

    fun shouldSentenceCap(textBeforeCursor: String, variation: FieldVariation): Boolean {
        if (variation != FieldVariation.NORMAL) return false
        val trimmed = textBeforeCursor.trimEnd()
        if (trimmed.isEmpty()) return true
        return trimmed.last() in ".?!"
    }

    fun previousWordLength(textBeforeCursor: String): Int {
        if (textBeforeCursor.isEmpty()) return 0
        var i = textBeforeCursor.length
        while (i > 0 && textBeforeCursor[i - 1].isWhitespace()) i--
        val wordEnd = i
        while (i > 0 && !textBeforeCursor[i - 1].isWhitespace()) i--
        return textBeforeCursor.length - i
    }
}
