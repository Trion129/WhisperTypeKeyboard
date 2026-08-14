package me.trion.whispertype.ime

data class EmojiItem(
    val emoji: String,
    val group: String,
    val subgroup: String,
    val name: String,
    val keywords: List<String>,
    val tones: List<String> = emptyList(),
) {
    val toneCapable: Boolean get() = tones.isNotEmpty()
}

class EmojiCatalog(
    val groups: List<String>,
    val items: List<EmojiItem>,
) {
    private val byEmoji = items.associateBy { it.emoji }

    fun inGroup(group: String): List<EmojiItem> = items.filter { it.group == group }

    fun search(query: String): List<EmojiItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return items.filter { item ->
            item.name.lowercase().contains(q) ||
                item.keywords.any { it.lowercase().contains(q) } ||
                item.emoji == query.trim()
        }
    }

    fun recents(emojis: List<String>, cap: Int = 24): List<EmojiItem> {
        val out = ArrayList<EmojiItem>(minOf(cap, emojis.size))
        for (e in emojis) {
            val item = byEmoji[e] ?: EmojiItem(e, "Recents", "", e, emptyList())
            out.add(item)
            if (out.size == cap) break
        }
        return out
    }

    companion object {
        val DEFAULT_GROUPS = listOf(
            "Smileys & Emotion",
            "People & Body",
            "Animals & Nature",
            "Food & Drink",
            "Travel & Places",
            "Activities",
            "Objects",
            "Symbols",
            "Flags",
        )

        fun parse(json: String): EmojiCatalog {
            val groups = parseStringArray(extractArray(json, "groups")) ?: DEFAULT_GROUPS
            val itemsRaw = extractArray(json, "items") ?: "[]"
            val items = parseItems(itemsRaw)
            return EmojiCatalog(groups, items)
        }

        private fun extractArray(json: String, key: String): String? {
            val needle = "\"$key\""
            val idx = json.indexOf(needle)
            if (idx < 0) return null
            val bracket = json.indexOf('[', idx)
            if (bracket < 0) return null
            var depth = 0
            var inStr = false
            var escape = false
            for (i in bracket until json.length) {
                val c = json[i]
                if (inStr) {
                    if (escape) escape = false
                    else if (c == '\\') escape = true
                    else if (c == '"') inStr = false
                    continue
                }
                when (c) {
                    '"' -> inStr = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return json.substring(bracket, i + 1)
                    }
                }
            }
            return null
        }

        private fun parseItems(array: String): List<EmojiItem> {
            val items = mutableListOf<EmojiItem>()
            var i = 1
            val end = array.length - 1
            while (i < end) {
                while (i < end && array[i] != '{') i++
                if (i >= end) break
                var depth = 0
                var inStr = false
                var escape = false
                val start = i
                var close = -1
                for (j in i until array.length) {
                    val c = array[j]
                    if (inStr) {
                        if (escape) escape = false
                        else if (c == '\\') escape = true
                        else if (c == '"') inStr = false
                        continue
                    }
                    when (c) {
                        '"' -> inStr = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                close = j
                                break
                            }
                        }
                    }
                }
                if (close < 0) break
                parseItem(array.substring(start, close + 1))?.let { items.add(it) }
                i = close + 1
            }
            return items
        }

        private fun parseItem(obj: String): EmojiItem? {
            val emoji = stringField(obj, "emoji") ?: return null
            val group = stringField(obj, "group") ?: ""
            val subgroup = stringField(obj, "subgroup") ?: ""
            val name = stringField(obj, "name") ?: emoji
            val keywords = parseStringArray(extractArray(obj, "keywords")) ?: emptyList()
            val tones = parseStringArray(extractArray(obj, "tones")) ?: emptyList()
            return EmojiItem(emoji, group, subgroup, name, keywords, tones)
        }

        private fun parseStringArray(raw: String?): List<String>? {
            if (raw == null) return null
            val body = raw.trim()
            if (body == "[]") return emptyList()
            val out = mutableListOf<String>()
            var i = 1
            val end = body.length - 1
            while (i < end) {
                while (i < end && body[i] != '"') i++
                if (i >= end) break
                val sb = StringBuilder()
                i++
                while (i < end) {
                    val c = body[i]
                    if (c == '\\') {
                        if (i + 1 < end) {
                            sb.append(body[i + 1])
                            i += 2
                        } else break
                    } else if (c == '"') {
                        i++
                        break
                    } else {
                        sb.append(c)
                        i++
                    }
                }
                out.add(sb.toString())
            }
            return out
        }

        private fun stringField(obj: String, name: String): String? {
            val key = "\"$name\""
            val idx = obj.indexOf(key)
            if (idx < 0) return null
            var i = idx + key.length
            while (i < obj.length && (obj[i] == ':' || obj[i].isWhitespace())) i++
            if (i >= obj.length || obj[i] != '"') return null
            val sb = StringBuilder()
            i++
            while (i < obj.length) {
                val c = obj[i]
                if (c == '\\') {
                    if (i + 1 >= obj.length) return null
                    sb.append(obj[i + 1])
                    i += 2
                } else if (c == '"') {
                    return sb.toString()
                } else {
                    sb.append(c)
                    i++
                }
            }
            return null
        }
    }
}
