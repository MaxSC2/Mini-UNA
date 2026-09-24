package com.maxsc2.miniuna

// Выгрузка заметок и списков для синка (файл-мост к Компаньону/Una-desktop).
// Формат — обычный JSON, читается любым парсером: {"app":"mini-una","v":1,
// "notes":[{"text","time"}],"lists":{name:[items]}}.
// Ручной сериализатор/парсер вместо org.json: чистый Kotlin, покрыт юнит-тестами
// без Android-рантайма.

data class NotesExport(val notes: List<NotesStore.Note>, val lists: Map<String, List<String>>)

fun escapeJson(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.toString()
}

fun buildNotesExport(notes: List<NotesStore.Note>, lists: Map<String, List<String>>): String {
    val sb = StringBuilder()
    sb.append("{\"app\":\"mini-una\",\"v\":1,\"notes\":[")
    notes.forEachIndexed { i, n ->
        if (i > 0) sb.append(',')
        sb.append("{\"text\":\"").append(escapeJson(n.text)).append("\",\"time\":").append(n.time).append('}')
    }
    sb.append("],\"lists\":{")
    lists.entries.forEachIndexed { i, (name, items) ->
        if (i > 0) sb.append(',')
        sb.append('"').append(escapeJson(name)).append("\":[")
        items.forEachIndexed { j, it ->
            if (j > 0) sb.append(',')
            sb.append('"').append(escapeJson(it)).append('"')
        }
        sb.append(']')
    }
    return sb.append("}}").toString()
}

private class JsonReader(val s: String) {
    var i = 0

    fun done(): Boolean {
        ws()
        return i == s.length
    }

    private fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun expect(c: Char): Boolean {
        ws()
        if (i < s.length && s[i] == c) {
            i++
            return true
        }
        return false
    }

    private fun parseString(): String? {
        ws()
        if (i >= s.length || s[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i++]
            if (c == '"') return sb.toString()
            if (c == '\\') {
                if (i >= s.length) return null
                when (s[i++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'u' -> {
                        if (i + 4 > s.length) return null
                        val code = s.substring(i, i + 4).toIntOrNull(16) ?: return null
                        sb.append(code.toChar())
                        i += 4
                    }
                    else -> return null
                }
            } else {
                sb.append(c)
            }
        }
        return null
    }

    private fun parseLong(): Long? {
        ws()
        val start = i
        if (i < s.length && s[i] == '-') i++
        while (i < s.length && s[i].isDigit()) i++
        if (i == start) return null
        return s.substring(start, i).toLongOrNull()
    }

    fun parseValue(): Any? {
        ws()
        if (i >= s.length) return null
        return when (s[i]) {
            '"' -> parseString()
            '{' -> run {
                i++
                val m = mutableMapOf<String, Any?>()
                ws()
                if (expect('}')) return m
                while (true) {
                    val k = parseString() ?: return null
                    if (!expect(':')) return null
                    m[k] = parseValue()
                    ws()
                    if (expect('}')) return m
                    if (!expect(',')) return null
                }
            }
            '[' -> run {
                i++
                val l = mutableListOf<Any?>()
                ws()
                if (expect(']')) return l
                while (true) {
                    l.add(parseValue())
                    ws()
                    if (expect(']')) return l
                    if (!expect(',')) return null
                }
            }
            't' -> if (s.startsWith("true", i)) {
                i += 4
                true
            } else {
                null
            }
            'f' -> if (s.startsWith("false", i)) {
                i += 5
                false
            } else {
                null
            }
            'n' -> if (s.startsWith("null", i)) {
                i += 4
                null
            } else {
                null
            }
            else -> parseLong()
        }
    }
}

fun parseNotesExport(s: String): NotesExport? {
    return try {
        if (s.isBlank()) return null
        val r = JsonReader(s)
        val root = r.parseValue() as? Map<*, *> ?: return null
        if (!r.done()) return null
        if (root["app"] != "mini-una") return null
        val notes = mutableListOf<NotesStore.Note>()
        for (o in (root["notes"] as? List<*>).orEmpty()) {
            val m = o as? Map<*, *> ?: continue
            val text = (m["text"] as? String)?.trim().orEmpty()
            if (text.isEmpty()) continue
            notes.add(NotesStore.Note(text, (m["time"] as? Long) ?: 0L))
        }
        val lists = mutableMapOf<String, List<String>>()
        for ((k, v) in (root["lists"] as? Map<*, *>).orEmpty()) {
            val name = k as? String ?: continue
            val items = (v as? List<*>)?.mapNotNull {
                (it as? String)?.trim()?.ifBlank { null }
            } ?: continue
            lists[name] = items
        }
        NotesExport(notes, lists)
    } catch (_: Throwable) {
        null
    }
}
