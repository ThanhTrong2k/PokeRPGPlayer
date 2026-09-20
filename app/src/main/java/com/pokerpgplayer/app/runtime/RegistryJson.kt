package com.pokerpgplayer.app.runtime

/**
 * Build-compatibility correction. A minimal, dependency-free JSON codec
 * scoped only to [MirrorRuntimeWorkspaceService]'s own registry.json —
 * flat objects of objects, string/number/boolean/null scalar values, no
 * arrays. Exists because `org.json.JSONObject` is not safe to use inside
 * a plain-JVM-tested path in a real Android Gradle project: local JVM
 * unit tests (`testDebugUnitTest`, no Robolectric) resolve `org.json.*`
 * from the Android platform's own stub `android.jar`, whose method bodies
 * throw at runtime ("not mocked") rather than the real Maven `org.json`
 * artifact — a real-device/production `MirrorRuntimeWorkspaceService`
 * call works fine (the real platform's `org.json` implementation is
 * used there), but the exact same code fails every local JVM test that
 * exercises the registry. This class's own kdoc precedent
 * ([com.pokerpgplayer.app.data.config.RuntimeConfigOverlayService], see
 * its own note on why `org.json.JSONObject` was rejected there too,
 * albeit for a different reason — JSON5 tolerance) already establishes
 * that this codebase avoids `org.json.JSONObject` in any file that must
 * stay plain-JVM-testable; this file continues that precedent for the
 * one remaining place it was still used in a JVM-tested path.
 *
 * Deliberately NOT a general-purpose JSON library: no array support (the
 * registry schema never has one), no streaming, no annotations. Reading
 * returns a generic `Map<String, Any?>` tree (object values become
 * nested maps; unknown/extra keys are preserved harmlessly, never
 * rejected) so an existing on-device `registry.json` written by the
 * previous `org.json`-based implementation — or any future entry with an
 * extra key — still parses without change; writing always emits
 * standard, strictly-valid JSON text, so nothing else that might ever
 * read this file needs to know this class exists.
 */
internal object RegistryJson {

    /**
     * Parses [text] as a single JSON object, returning `emptyMap()` for
     * blank/invalid input rather than throwing — callers already treat
     * "no registry yet" and "corrupted registry" the same way.
     *
     * Sprint53.2 RegistryJVM FINAL integrity correction: parsing the root
     * object is not enough — this also requires that nothing but
     * whitespace follows it. Without this, `{"a":{}} trailing-garbage` or
     * two concatenated objects (`{"a":{}}{"b":{}}`) would both be accepted
     * silently, with only the first object's content ever read back. A
     * corrupted/truncated-then-appended `registry.json` must fail safe
     * (surface as "corrupted, no usable registry"), not silently ignore
     * the corruption.
     */
    fun parseObject(text: String): Map<String, Any?> {
        if (text.isBlank()) return emptyMap()
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.requireAtEnd()
        @Suppress("UNCHECKED_CAST")
        return (value as? Map<String, Any?>) ?: emptyMap()
    }

    fun serializeObject(map: Map<String, Any?>): String = buildString { appendObject(map) }

    private fun StringBuilder.appendObject(map: Map<String, Any?>) {
        append('{')
        var first = true
        for ((key, value) in map) {
            if (!first) append(',')
            first = false
            append('"').append(escape(key)).append("\":")
            appendValue(value)
        }
        append('}')
    }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> append('"').append(escape(value)).append('"')
            is Boolean -> append(if (value) "true" else "false")
            is Int, is Long -> append(value.toString())
            is Number -> append(value.toString())
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                appendObject(value as Map<String, Any?>)
            }
            else -> append('"').append(escape(value.toString())).append('"')
        }
    }

    private fun escape(raw: String): String = buildString {
        for (c in raw) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) {
                    append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    append(c)
                }
            }
        }
    }

    /** Small recursive-descent parser for exactly the JSON subset this file's schema needs: object, string, number, true/false/null. No array support — never needed here. Malformed input surfaces as a thrown exception, which every caller in [MirrorRuntimeWorkspaceService] already wraps in `runCatching`/`try`-`catch` and treats as "no usable registry," matching this class's prior `org.json`-based behavior exactly. */
    private class Parser(private val s: String) {
        private var i = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (i >= s.length) return null
            return when {
                s[i] == '{' -> parseObjectValue()
                s[i] == '"' -> parseString()
                s.startsWith("true", i) -> { i += 4; true }
                s.startsWith("false", i) -> { i += 5; false }
                s.startsWith("null", i) -> { i += 4; null }
                else -> parseNumber()
            }
        }

        private fun parseObjectValue(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            expect('{')
            skipWhitespace()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> { i++; continue }
                    '}' -> { i++; break }
                    else -> throw IllegalArgumentException("Malformed registry JSON: expected ',' or '}' at index $i")
                }
            }
            return map
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < s.length && s[i] != '"') {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    i++
                    when (s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            val hex = s.substring(i + 1, i + 5)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(s[i])
                    }
                    i++
                } else {
                    sb.append(c)
                    i++
                }
            }
            expect('"')
            return sb.toString()
        }

        private fun parseNumber(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            val numStr = s.substring(start, i)
            if (numStr.isEmpty()) throw IllegalArgumentException("Malformed registry JSON: expected a value at index $i")
            return numStr.toLongOrNull() ?: numStr.toDouble()
        }

        private fun peek(): Char? = if (i < s.length) s[i] else null

        private fun expect(c: Char) {
            skipWhitespace()
            if (i >= s.length || s[i] != c) throw IllegalArgumentException("Malformed registry JSON: expected '$c' at index $i")
            i++
        }

        private fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        /**
         * Sprint53.2 RegistryJVM FINAL integrity correction. Called once,
         * immediately after the single root value has been parsed: skips
         * any trailing whitespace and then requires the input to be fully
         * consumed. Any remaining non-whitespace content — a stray
         * character, a second concatenated object, truncated-then-appended
         * bytes — throws rather than being silently discarded.
         */
        fun requireAtEnd() {
            skipWhitespace()
            if (i < s.length) {
                throw IllegalArgumentException("Malformed registry JSON: unexpected trailing content at index $i")
            }
        }
    }
}
