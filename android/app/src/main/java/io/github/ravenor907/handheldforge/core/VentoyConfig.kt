package io.github.ravenor907.handheldforge.core

import java.io.IOException

/**
 * "Boot next": sets which image Ventoy starts on its own, through Ventoy's documented plugin file
 * /ventoy/ventoy.json ("control" settings). With it set, Ventoy shows its menu for a few seconds
 * and then boots that image, so a handheld whose controller doesn't work in Ventoy's menu still
 * gets there with no input. Pressing any key during the countdown stops it, as usual.
 *
 * Everything else in an existing ventoy.json is kept as it was.
 */
object VentoyConfig {
    const val PATH = "ventoy/ventoy.json"
    const val TIMEOUT_SECONDS = 5

    private const val DEFAULT = "VTOY_DEFAULT_IMAGE"
    private const val TIMEOUT = "VTOY_MENU_TIMEOUT"
    // Ventoy's second menu (normal mode, GRUB2 mode, checksum...) would wait for a button press.
    private const val SECONDARY = "VTOY_SECONDARY_BOOT_MENU"
    private val OURS = setOf(DEFAULT, TIMEOUT, SECONDARY)

    /** What's set to boot next on a Ventoy drive's image partition, or null. */
    fun bootNext(vol: ExFatVolume): String? = bootNext(vol.readFile(PATH)?.toString(Charsets.UTF_8))

    /** Sets (or with null, clears) the image to boot next, keeping the rest of ventoy.json. */
    fun setBootNext(vol: ExFatVolume, image: String?) {
        val old = vol.readFile(PATH)?.let { text(it) }
        val new = withBootNext(old, image)
        if (new == old) return
        if (new == null) vol.deleteFile(PATH) else vol.writeFile(PATH, new.toByteArray(Charsets.UTF_8))
    }

    /** The file's bytes as text. Bytes that aren't UTF-8 would be lost in a rewrite, so they are refused. */
    private fun text(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        throw IOException("ventoy.json on the drive isn't saved as UTF-8 text. Fix or remove it on a computer, then try again.")
    }

    /** The image set to boot next (a name in the drive's root), or null. */
    fun bootNext(json: String?): String? {
        val root = parse(json) ?: return null
        val control = root["control"] as? List<*> ?: return null
        val path = control.firstNotNullOfOrNull { (it as? Map<*, *>)?.get(DEFAULT) as? String } ?: return null
        return path.removePrefix("/").takeIf { it.isNotEmpty() }
    }

    /**
     * The new ventoy.json with [image] set to boot next (or the setting removed when null).
     * Returns null when nothing is left in the file, meaning it can be deleted.
     */
    fun withBootNext(json: String?, image: String?): String? {
        val root = parse(json) ?: LinkedHashMap()
        val old = when (val c = root["control"]) {
            null -> emptyList<Any?>()
            is List<*> -> c
            else -> throw IOException("ventoy.json on the drive has a \"control\" setting that isn't a list. Fix or remove it on a computer.")
        }
        // Our settings are taken out; an entry that also holds other settings keeps those.
        val control = ArrayList<Any?>()
        for (e in old) {
            if (e is Map<*, *> && e.keys.any { it in OURS }) {
                val others = LinkedHashMap<Any?, Any?>()
                for ((k, v) in e) if (k !in OURS) others[k] = v
                if (others.isNotEmpty()) control.add(others)
            } else {
                control.add(e)
            }
        }
        if (image != null) {
            control.add(linkedMapOf(DEFAULT to "/$image"))
            control.add(linkedMapOf(TIMEOUT to "$TIMEOUT_SECONDS"))
            control.add(linkedMapOf(SECONDARY to "0"))
        }
        if (control.isEmpty()) root.remove("control") else root["control"] = control
        if (root.isEmpty()) return null
        return JsonText.write(root) + "\n"
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(json: String?): LinkedHashMap<String, Any?>? {
        if (json == null || json.isBlank()) return null
        val v = try {
            JsonText.parse(json.removePrefix("﻿"))
        } catch (e: IOException) {
            throw IOException("ventoy.json on the drive has an error (${e.message}). Fix or remove it on a computer, then try again.")
        }
        return v as? LinkedHashMap<String, Any?>
            ?: throw IOException("ventoy.json on the drive isn't a JSON object. Fix or remove it on a computer.")
    }
}

/**
 * Small JSON reader and writer that keeps key order and numbers exactly as written,
 * so rewriting someone's ventoy.json changes only what we meant to change.
 */
object JsonText {
    /** A number kept as its original text. */
    data class Num(val text: String)

    fun parse(s: String): Any? {
        val p = Parser(s)
        val v = p.value()
        p.ws()
        if (p.i != s.length) p.fail("unexpected text after the end")
        return v
    }

    fun write(v: Any?, indent: String = ""): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Num -> v.text
        is Number -> v.toString()
        is String -> quote(v)
        is Map<*, *> -> if (v.isEmpty()) "{}" else v.entries.joinToString(",\n", "{\n", "\n$indent}") {
            "$indent    ${quote(it.key.toString())}: ${write(it.value, "$indent    ")}"
        }
        is List<*> -> if (v.isEmpty()) "[]" else v.joinToString(",\n", "[\n", "\n$indent]") { "$indent    ${write(it, "$indent    ")}" }
        else -> throw IllegalArgumentException("can't write ${v::class.java.simpleName} as JSON")
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private const val MAX_DEPTH = 64

    private class Parser(val s: String) {
        var i = 0
        private var depth = 0

        fun fail(msg: String): Nothing {
            val line = s.substring(0, minOf(i, s.length)).count { it == '\n' } + 1
            throw IOException("$msg on line $line")
        }

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("unexpected '$c'")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail("unexpected text")
            i += w.length
            return v
        }

        private fun digit() = i < s.length && s[i] in '0'..'9'

        /** A number as JSON defines it: -, digits (no extra leading zero), then an optional fraction and exponent. */
        private fun num(): Num {
            val start = i
            if (s[i] == '-') i++
            if (!digit()) fail("bad number")
            if (s[i] == '0') i++ else while (digit()) i++
            if (i < s.length && s[i] == '.') {
                i++
                if (!digit()) fail("bad number")
                while (digit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (!digit()) fail("bad number")
                while (digit()) i++
            }
            // Anything else stuck to the number ("1.2.3", "0x10") makes it not a number.
            if (i < s.length && (s[i].isLetterOrDigit() || s[i] in "+-.")) fail("bad number")
            return Num(s.substring(start, i))
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unfinished text in quotes")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("unfinished escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad \\u escape")
                                var code = 0
                                for (k in 0 until 4) {
                                    val h = Character.digit(s[i + k], 16)
                                    if (h < 0 || s[i + k].code > 0x7F) fail("bad \\u escape")
                                    code = code * 16 + h
                                }
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("bad escape \\$e")
                        }
                    }
                    else -> {
                        if (c.code < 0x20) {
                            i--
                            fail("line break or control character inside quotes")
                        }
                        sb.append(c)
                    }
                }
            }
        }

        private fun deeper() {
            if (++depth > MAX_DEPTH) fail("too many levels of brackets")
        }

        private fun obj(): LinkedHashMap<String, Any?> {
            deeper()
            return members().also { depth-- }
        }

        private fun arr(): ArrayList<Any?> {
            deeper()
            return items().also { depth-- }
        }

        private fun members(): LinkedHashMap<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("expected a name in quotes")
                val k = str()
                // Only one of two settings with the same name could be kept.
                if (k in m) fail("\"$k\" appears twice in one object")
                ws()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++
                m[k] = value()
                ws()
                if (i >= s.length) fail("unexpected end")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> { i--; fail("expected ',' or '}'") }
                }
            }
        }

        private fun items(): ArrayList<Any?> {
            i++
            val a = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return a }
            while (true) {
                a.add(value())
                ws()
                if (i >= s.length) fail("unexpected end")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return a
                    else -> { i--; fail("expected ',' or ']'") }
                }
            }
        }
    }
}
