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
    val icon: Boolean = false
)

object KeyboardLayout {
    enum class Mode { LETTERS, NUMBERS, SYMBOLS }

    val letters: List<List<KeyDef>> = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map { c ->
            KeyDef(KeyType.CHAR, c, c.uppercase(), c[0].code)
        },
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map { c ->
            KeyDef(KeyType.CHAR, c, c.uppercase(), c[0].code)
        },
        listOf(
            KeyDef(KeyType.SHIFT, "⇧", weight = 1.4f, icon = true),
            *listOf("z", "x", "c", "v", "b", "n", "m").map { c ->
                KeyDef(KeyType.CHAR, c, c.uppercase(), c[0].code)
            }.toTypedArray(),
            KeyDef(KeyType.BACKSPACE, "⌫", weight = 1.4f, icon = true)
        ),
        listOf(
            KeyDef(KeyType.MODE_123, "?123", weight = 1.4f),
            KeyDef(KeyType.COMMA, ",", ","),
            KeyDef(KeyType.MIC, "mic", weight = 1.2f, icon = true),
            KeyDef(KeyType.SPACE, "space", weight = 4.2f),
            KeyDef(KeyType.PERIOD, ".", "."),
            KeyDef(KeyType.ENTER, "⏎", weight = 1.6f)
        )
    )

    val numbers: List<List<KeyDef>> = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map {
            KeyDef(KeyType.CHAR, it, it, it[0].code)
        },
        listOf("@", "#", "$", "%", "&", "-", "+", "(", ")").map {
            KeyDef(KeyType.CHAR, it, it, it[0].code)
        },
        listOf(
            KeyDef(KeyType.MODE_SYMBOLS, "=\\<", weight = 1.4f),
            *listOf("*", "\"", "'", ":", ";", "!", "?").map {
                KeyDef(KeyType.CHAR, it, it, it[0].code)
            }.toTypedArray(),
            KeyDef(KeyType.BACKSPACE, "⌫", weight = 1.4f, icon = true)
        ),
        listOf(
            KeyDef(KeyType.MODE_ABC, "ABC", weight = 1.4f),
            KeyDef(KeyType.COMMA, ",", ","),
            KeyDef(KeyType.MIC, "mic", weight = 1.2f, icon = true),
            KeyDef(KeyType.SPACE, "space", weight = 4.2f),
            KeyDef(KeyType.PERIOD, ".", "."),
            KeyDef(KeyType.ENTER, "⏎", weight = 1.6f)
        )
    )

    val symbols: List<List<KeyDef>> = listOf(
        listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map {
            KeyDef(KeyType.CHAR, it, it)
        },
        listOf("£", "€", "¥", "^", "°", "=", "{", "}", "\\").map {
            KeyDef(KeyType.CHAR, it, it)
        },
        listOf(
            KeyDef(KeyType.MODE_123, "?123", weight = 1.4f),
            *listOf("%", "©", "®", "™", "✓", "[", "]").map {
                KeyDef(KeyType.CHAR, it, it)
            }.toTypedArray(),
            KeyDef(KeyType.BACKSPACE, "⌫", weight = 1.4f, icon = true)
        ),
        listOf(
            KeyDef(KeyType.MODE_ABC, "ABC", weight = 1.4f),
            KeyDef(KeyType.COMMA, ",", ","),
            KeyDef(KeyType.MIC, "mic", weight = 1.2f, icon = true),
            KeyDef(KeyType.SPACE, "space", weight = 4.2f),
            KeyDef(KeyType.PERIOD, ".", "."),
            KeyDef(KeyType.ENTER, "⏎", weight = 1.6f)
        )
    )

    fun rowsFor(mode: Mode): List<List<KeyDef>> = when (mode) {
        Mode.LETTERS -> letters
        Mode.NUMBERS -> numbers
        Mode.SYMBOLS -> symbols
    }
}
