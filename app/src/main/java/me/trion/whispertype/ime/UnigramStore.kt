package me.trion.whispertype.ime

import java.io.File

class UnigramStore(private val file: File) {
    fun snapshot(): Map<String, Int> = load()

    fun learn(word: String): Map<String, Int> {
        val normalized = word.lowercase()
        if (normalized.length < 2 || normalized.any { !it.isLetter() }) return load()
        val next = load().toMutableMap()
        if (!next.containsKey(normalized)) {
            while (next.size >= MAX_ENTRIES) {
                val drop = next.minWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                next.remove(drop.key)
            }
        }
        next[normalized] = (next[normalized] ?: 0) + 1
        save(next)
        return next
    }

    fun clear() {
        save(emptyMap())
    }

    private fun load(): Map<String, Int> {
        if (!file.exists()) return emptyMap()
        return try {
            parseObject(file.readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun save(map: Map<String, Int>) {
        file.parentFile?.mkdirs()
        file.writeText(encodeObject(map))
    }

    companion object {
        const val MAX_ENTRIES = 2000

        internal fun encodeObject(map: Map<String, Int>): String {
            return map.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                "${jsonString(k)}:$v"
            }
        }

        internal fun parseObject(raw: String): Map<String, Int> {
            val body = raw.trim()
            if (body.isEmpty() || body == "{}") return emptyMap()
            if (!body.startsWith("{") || !body.endsWith("}")) return emptyMap()
            val out = linkedMapOf<String, Int>()
            var i = 1
            val end = body.length - 1
            while (i < end) {
                while (i < end && body[i] != '"') i++
                if (i >= end) break
                i++
                val keySb = StringBuilder()
                while (i < end) {
                    val c = body[i]
                    if (c == '\\') {
                        if (i + 1 >= end) return emptyMap()
                        keySb.append(body[i + 1])
                        i += 2
                    } else if (c == '"') {
                        i++
                        break
                    } else {
                        keySb.append(c)
                        i++
                    }
                }
                while (i < end && (body[i] == ':' || body[i].isWhitespace())) i++
                val numStart = i
                while (i < end && body[i].isDigit()) i++
                val count = body.substring(numStart, i).toIntOrNull() ?: return emptyMap()
                out[keySb.toString()] = count
                while (i < end && body[i] != '"') i++
            }
            return out
        }

        private fun jsonString(value: String): String {
            val sb = StringBuilder("\"")
            for (c in value) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    else -> sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
