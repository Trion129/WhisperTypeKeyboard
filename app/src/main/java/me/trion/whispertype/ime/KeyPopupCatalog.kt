package me.trion.whispertype.ime

object KeyPopupCatalog {
    private val letters = mapOf(
        "a" to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"),
        "e" to listOf("è", "é", "ê", "ë", "ē", "ė", "ę"),
        "i" to listOf("ì", "í", "î", "ï", "ī", "į"),
        "o" to listOf("ò", "ó", "ô", "ö", "õ", "ø", "ō", "œ"),
        "u" to listOf("ù", "ú", "û", "ü", "ū"),
        "y" to listOf("ÿ"),
        "c" to listOf("ç", "ć", "č"),
        "n" to listOf("ñ", "ń"),
        "s" to listOf("ś", "š", "ß"),
        "l" to listOf("ł"),
        "z" to listOf("ź", "ż", "ž"),
    )

    private val symbols = mapOf(
        "-" to listOf("–", "—", "•"),
        "/" to listOf("\\", "|"),
        "$" to listOf("¢", "£", "€", "¥", "₹"),
        "%" to listOf("‰"),
        "*" to listOf("★", "†", "‡"),
        "\"" to listOf("“", "”", "«", "»"),
        "'" to listOf("‘", "’", "`"),
        "." to listOf("…", "·"),
        "," to listOf("'"),
        "!" to listOf("¡"),
        "?" to listOf("¿"),
        "0" to listOf("⁰"),
        "1" to listOf("¹", "½", "⅓", "¼"),
        "2" to listOf("²", "⅔"),
        "3" to listOf("³", "¾"),
        "4" to listOf("⁴"),
        "5" to listOf("⁵"),
        "6" to listOf("⁶"),
        "7" to listOf("⁷"),
        "8" to listOf("⁸"),
        "9" to listOf("⁹"),
    )

    private val space = listOf("😊", "😂", "👍", "❤️", "🔥", "🎉", "🙏")

    fun popupsFor(base: String): List<String> {
        if (base == " " || base.equals("space", ignoreCase = true)) return space
        val key = base.lowercase()
        return letters[key] ?: symbols[key] ?: emptyList()
    }

    fun popupsFor(base: String, uppercase: Boolean): List<String> {
        val raw = popupsFor(base)
        if (!uppercase) return raw
        return raw.map { if (it == "ß") it else it.uppercase() }
    }
}
