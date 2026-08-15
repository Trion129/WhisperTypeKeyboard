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

    fun previousGraphemeLength(text: String): Int {
        if (text.isEmpty()) return 0
        var i = text.length
        fun prev(): Int = Character.codePointBefore(text, i)
        fun back() {
            i = Character.offsetByCodePoints(text, i, -1)
        }

        back()
        val last = Character.codePointAt(text, i)
        if (isClusterExtender(last) || last == KEYCAP) {
            while (i > 0 && isClusterExtender(prev())) back()
            if (i > 0) back()
        }
        while (i > 0 && prev() == ZWJ) {
            back()
            if (i == 0) break
            back()
            while (i > 0 && isClusterExtender(prev())) back()
        }
        if (i > 0 && isRegionalIndicator(Character.codePointAt(text, i)) && isRegionalIndicator(prev())) {
            back()
        }
        return text.length - i
    }

    private fun isClusterExtender(cp: Int): Boolean {
        return cp == 0xFE0E ||
            cp == 0xFE0F ||
            cp == KEYCAP ||
            cp in 0xFE00..0xFE0D ||
            cp in 0x1F3FB..0x1F3FF ||
            cp in 0xE0020..0xE007F ||
            Character.getType(cp) == Character.NON_SPACING_MARK.toInt() ||
            Character.getType(cp) == Character.ENCLOSING_MARK.toInt() ||
            Character.getType(cp) == Character.COMBINING_SPACING_MARK.toInt()
    }

    private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    private const val ZWJ = 0x200D
    private const val KEYCAP = 0x20E3
}
