package me.trion.whispertype.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

enum class FieldVariation { NORMAL, URI, EMAIL, PASSWORD, NUMBER }

enum class EnterAction { EDITOR_ACTION, NEW_LINE, KEY_EVENT }

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

    /**
     * Auto capitalization for dictation. Models capitalize the first word of
     * every transcript (or of none), so make it follow the text before the
     * cursor: a capital when a new sentence starts, lowercase mid-sentence.
     * Words that look deliberate ("I", "I'm", "OK", "iPhone") stay as they are.
     */
    fun matchDictationCase(transcript: String, textBeforeCursor: String, variation: FieldVariation): String {
        if (variation != FieldVariation.NORMAL) return transcript
        val firstWord = transcript.takeWhile { !it.isWhitespace() }
        if (firstWord.isEmpty() || firstWord.drop(1).any { it.isUpperCase() }) return transcript
        val sentenceStart = textBeforeCursor.trimEnd(' ', '\t').endsWith('\n') ||
            shouldSentenceCap(textBeforeCursor, variation)
        if (sentenceStart) return transcript.replaceFirstChar { it.uppercaseChar() }
        if (firstWord.length < 2 || !firstWord[0].isUpperCase()) return transcript
        if (firstWord[0] == 'I' && !firstWord[1].isLetter()) return transcript
        return transcript.replaceFirstChar { it.lowercaseChar() }
    }

    fun enterAction(imeOptions: Int, inputType: Int, preferNewLine: Boolean): EnterAction {
        val multiLine = inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (preferNewLine && multiLine) return EnterAction.NEW_LINE
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED &&
            imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0
        ) {
            return EnterAction.EDITOR_ACTION
        }
        return EnterAction.KEY_EVENT
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
