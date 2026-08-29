package com.bfg.watchfaces.appcore

/**
 * A minimal JSON reader, just enough for a stored face.
 *
 * Hand-rolled rather than adding a dependency: docs/SPEC.md makes the stored
 * parameter file the community catalog format, and that format should stay
 * readable by anything -- including :generator, which is deliberately
 * dependency-free. When the catalog becomes real, THIS is the thing to replace
 * with a schema-validated reader in :generator. It is not a reason to pull a
 * serialization framework into a dev tool today.
 *
 * Objects, arrays, strings, numbers, booleans, null. No streaming, no comments.
 * A face is ~5KB.
 */
object Json {

    fun parse(src: String): Any? {
        val p = Parser(src)
        val v = p.value()
        p.ws()
        require(p.done()) { "trailing content at offset ${p.i}" }
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()

    fun str(m: Map<String, Any?>, k: String, def: String = ""): String = m[k] as? String ?: def
    fun num(m: Map<String, Any?>, k: String, def: Double): Double = (m[k] as? Double) ?: def
    fun bool(m: Map<String, Any?>, k: String, def: Boolean): Boolean = (m[k] as? Boolean) ?: def

    /** Escapes a string for embedding in emitted JSON. */
    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    private class Parser(val s: String) {
        var i = 0
        fun done() = i >= s.length
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            ws()
            require(i < s.length) { "unexpected end of input" }
            val c = s[i]
            return when {
                c == '{' -> obj()
                c == '[' -> arr()
                c == '"' -> str()
                c == 't' -> lit("true", true)
                c == 'f' -> lit("false", false)
                c == 'n' -> lit("null", null)
                c == '-' || c.isDigit() -> num()
                else -> error("unexpected '$c' at offset $i")
            }
        }

        fun <T> lit(word: String, v: T): T {
            require(s.startsWith(word, i)) { "bad literal at offset $i" }
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                ws()
                require(s[i] == ':') { "expected ':' at offset $i" }
                i++
                m[k] = value()
                ws()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> error("expected ',' or '}' at offset $i")
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                ws()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> error("expected ',' or ']' at offset $i")
                }
            }
        }

        fun str(): String {
            require(s[i] == '"') { "expected a string at offset $i" }
            i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (val e = s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> error("bad escape '\\$e' at offset $i")
                    }
                    i++
                } else sb.append(s[i++])
            }
            i++
            return sb.toString()
        }

        fun num(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' ||
                        s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(start, i).toDouble()
        }
    }
}
