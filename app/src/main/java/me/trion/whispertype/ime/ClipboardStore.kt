package me.trion.whispertype.ime

import java.io.File
import java.util.UUID

data class ClipItem(
    val id: String,
    val text: String,
    val createdAtMs: Long,
)

sealed class CaptureResult {
    data class Added(val items: List<ClipItem>) : CaptureResult()
    object Ignored : CaptureResult()
}

class ClipboardStore(private val file: File) {
    fun snapshot(): List<ClipItem> = load()

    fun capture(text: String, nowMs: Long): CaptureResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_CHARS) return CaptureResult.Ignored
        val current = load().toMutableList()
        if (current.firstOrNull()?.text == trimmed) return CaptureResult.Ignored
        current.add(0, ClipItem(id = UUID.randomUUID().toString(), text = trimmed, createdAtMs = nowMs))
        val capped = current.take(MAX_ITEMS)
        save(capped)
        return CaptureResult.Added(capped)
    }

    fun delete(id: String) {
        val next = load().filterNot { it.id == id }
        save(next)
    }

    fun clear() {
        save(emptyList())
    }

    private fun load(): List<ClipItem> {
        if (!file.exists()) return emptyList()
        return try {
            parseArray(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(items: List<ClipItem>) {
        file.parentFile?.mkdirs()
        file.writeText(encodeArray(items))
    }

    companion object {
        const val MAX_ITEMS = 20
        const val MAX_CHARS = 8192

        internal fun encodeArray(items: List<ClipItem>): String {
            return items.joinToString(prefix = "[", postfix = "]") { item ->
                """{"id":${jsonString(item.id)},"text":${jsonString(item.text)},"createdAtMs":${item.createdAtMs}}"""
            }
        }

        internal fun parseArray(raw: String): List<ClipItem> {
            val body = raw.trim()
            if (body.isEmpty() || body == "[]") return emptyList()
            if (!body.startsWith("[") || !body.endsWith("]")) return emptyList()
            val items = mutableListOf<ClipItem>()
            var i = 1
            val end = body.length - 1
            while (i < end) {
                while (i < end && body[i] != '{') i++
                if (i >= end) break
                val close = body.indexOf('}', i)
                if (close < 0) break
                val obj = body.substring(i, close + 1)
                val id = field(obj, "id") ?: return emptyList()
                val text = field(obj, "text") ?: return emptyList()
                val created = field(obj, "createdAtMs")?.toLongOrNull() ?: return emptyList()
                items.add(ClipItem(id, text, created))
                i = close + 1
            }
            return items
        }

        private fun field(obj: String, name: String): String? {
            val key = "\"$name\""
            val idx = obj.indexOf(key)
            if (idx < 0) return null
            var i = idx + key.length
            while (i < obj.length && (obj[i] == ':' || obj[i].isWhitespace())) i++
            if (i >= obj.length) return null
            return if (obj[i] == '"') {
                val parsed = StringBuilder()
                i++
                while (i < obj.length) {
                    val c = obj[i]
                    if (c == '\\') {
                        if (i + 1 >= obj.length) return null
                        parsed.append(unescape(obj[i + 1]))
                        i += 2
                    } else if (c == '"') {
                        return parsed.toString()
                    } else {
                        parsed.append(c)
                        i++
                    }
                }
                null
            } else {
                val start = i
                while (i < obj.length && obj[i] != ',' && obj[i] != '}') i++
                obj.substring(start, i).trim()
            }
        }

        private fun jsonString(value: String): String {
            val sb = StringBuilder("\"")
            for (c in value) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }

        private fun unescape(c: Char): Char = when (c) {
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            else -> c
        }
    }
}
