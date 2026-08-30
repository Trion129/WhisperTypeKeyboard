package me.trion.whispertype.ime

enum class KeyType {
    CHAR,
    SHIFT,
    BACKSPACE,
    SPACE,
    ENTER,
    MODE_ABC,
    MODE_123,
    MODE_SYMBOLS,
    MODE_EMOJI,
    MIC,
    COMMA,
    PERIOD
}

data class KeyDef(
    val type: KeyType,
    val label: String = "",
    val shiftLabel: String = "",
    val code: Int = 0,
    val weight: Float = 1f,
    val icon: Boolean = false,
    val popupLabels: List<String> = emptyList()
)

object KeyboardLayout {
    enum class Mode { LETTERS, NUMBERS, SYMBOLS, EMOJI }

    private fun letter(c: String): KeyDef {
        return KeyDef(
            KeyType.CHAR,
            c,
            c.uppercase(),
            c[0].code,
            popupLabels = KeyPopupCatalog.popupsFor(c)
        )
    }

    private fun searchLetter(c: String): KeyDef = KeyDef(
        type = KeyType.CHAR,
        label = c,
        shiftLabel = c.uppercase(),
        code = c[0].code,
        popupLabels = emptyList(),
    )

    private fun symbol(c: String): KeyDef {
        return KeyDef(
            KeyType.CHAR,
            c,
            c,
            if (c.length == 1) c[0].code else 0,
            popupLabels = KeyPopupCatalog.popupsFor(c)
        )
    }

    private val bottomLetters = listOf(
        KeyDef(KeyType.MODE_123, "?123", weight = 1.4f),
        KeyDef(KeyType.MODE_EMOJI, "😀", weight = 1.2f),
        KeyDef(KeyType.COMMA, ",", ",", popupLabels = KeyPopupCatalog.popupsFor(",")),
        KeyDef(KeyType.MIC, "mic", weight = 1.2f, icon = true),
        KeyDef(KeyType.SPACE, "space", weight = 3.0f, popupLabels = KeyPopupCatalog.popupsFor(" ")),
        KeyDef(KeyType.PERIOD, ".", ".", popupLabels = KeyPopupCatalog.popupsFor(".")),
        KeyDef(KeyType.ENTER, "⏎", weight = 1.6f)
    )

    private val bottomOther = listOf(
        KeyDef(KeyType.MODE_ABC, "ABC", weight = 1.4f),
        KeyDef(KeyType.COMMA, ",", ",", popupLabels = KeyPopupCatalog.popupsFor(",")),
        KeyDef(KeyType.MIC, "mic", weight = 1.2f, icon = true),
        KeyDef(KeyType.SPACE, "space", weight = 4.2f, popupLabels = KeyPopupCatalog.popupsFor(" ")),
        KeyDef(KeyType.PERIOD, ".", ".", popupLabels = KeyPopupCatalog.popupsFor(".")),
        KeyDef(KeyType.ENTER, "⏎", weight = 1.6f)
    )

    private val emojiSearchThird = listOf(
        *listOf("z", "x", "c", "v", "b", "n", "m").map(::searchLetter).toTypedArray(),
        KeyDef(KeyType.BACKSPACE, "⌫", weight = 1.4f, icon = true),
    )

    private val bottomEmojiSearch = listOf(
        KeyDef(KeyType.MODE_ABC, "ABC", weight = 1.4f),
        KeyDef(KeyType.MODE_EMOJI, "😀", weight = 1.2f),
        KeyDef(KeyType.SPACE, "space", weight = 5.0f, popupLabels = emptyList()),
    )

    val letters: List<List<KeyDef>> = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map { letter(it) },
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map { letter(it) },
        listOf(
            KeyDef(KeyType.SHIFT, "⇧", weight = 1.4f, icon = true),
            *listOf("z", "x", "c", "v", "b", "n", "m").map { letter(it) }.toTypedArray(),
            KeyDef(KeyType.BACKSPACE, "⌫", weight = 1.4f, icon = true)
        ),
        bottomLetters
    )

    val emojiSearch: List<List<KeyDef>> = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map(::searchLetter),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map(::searchLetter),
        emojiSearchThird,
        bottomEmojiSearch,
    )

    val numbers: List<List<KeyDef>> = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { symbol(it) },
        listOf(
            symbol("@"),
            symbol("#"),
            symbol("$"),
            symbol("%"),
            symbol("&"),
            symbol("-"),
            symbol("+"),
            symbol("("),
            symbol(")"),
            symbol("/")
        ),
        listOf(
            KeyDef(KeyType.MODE_SYMBOLS, "=\\<", weight = 1.4f),
            *listOf("*", "\"", "'", ":", ";").map { symbol(it) }.toTypedArray(),
            symbol("!"),
            symbol("?"),
            KeyDef(KeyType.BACKSPACE, "⌫", weight = 1.4f, icon = true)
        ),
        bottomOther
    )

    val symbols: List<List<KeyDef>> = listOf(
        listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { symbol(it) },
        listOf("£", "€", "¥", "^", "°", "=", "{", "}", "\\").map { symbol(it) },
        listOf(
            KeyDef(KeyType.MODE_123, "?123", weight = 1.4f),
            *listOf("%", "©", "®", "™", "✓", "[", "]").map { symbol(it) }.toTypedArray(),
            KeyDef(KeyType.BACKSPACE, "⌫", weight = 1.4f, icon = true)
        ),
        bottomOther
    )

    val emoji: List<List<KeyDef>> = listOf(
        emptyList(),
        emptyList(),
        emptyList(),
        bottomOther
    )

    fun rowsFor(mode: Mode): List<List<KeyDef>> = when (mode) {
        Mode.LETTERS -> letters
        Mode.NUMBERS -> numbers
        Mode.SYMBOLS -> symbols
        Mode.EMOJI -> emoji
    }
}
