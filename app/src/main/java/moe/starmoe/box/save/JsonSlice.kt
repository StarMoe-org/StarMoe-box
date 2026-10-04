package moe.starmoe.box.save

/**
 * Finds a top-level member of a JSON object and returns its value's exact bytes, without parsing it into objects
 * and writing it back. Re-serializing would change number formatting (1.50 → 1.5, int64 precision), key order and
 * whitespace; cutting the original span keeps the game's own text.
 *
 * The scanner only tracks strings and nesting depth, which is all it needs to find value boundaries in valid
 * JSON. Callers validate the result as JSON afterwards.
 */
object JsonSlice {
    /**
     * The raw value of `key` in the top-level object of [json], or null when absent or the text is not one whole
     * object (a truncated save must not pass just because the wanted member came before the cut).
     */
    fun member(json: ByteArray, key: String): ByteArray? {
        val want = key.toByteArray(Charsets.UTF_8)
        var found: ByteArray? = null
        var i = skipWhitespace(json, skipBom(json))
        if (i >= json.size || json[i] != '{'.code.toByte()) return null
        i = skipWhitespace(json, i + 1)
        if (i < json.size && json[i] == '}'.code.toByte()) return null
        while (true) {
            if (i >= json.size || json[i] != '"'.code.toByte()) return null
            val keyStart = i + 1
            val keyEnd = stringEnd(json, i) ?: return null // index of the closing quote
            val isWanted = keyEnd - keyStart == want.size && json.copyOfRange(keyStart, keyEnd).contentEquals(want)
            i = skipWhitespace(json, keyEnd + 1)
            if (i >= json.size || json[i] != ':'.code.toByte()) return null
            i = skipWhitespace(json, i + 1)
            val valueStart = i
            val valueEnd = valueEnd(json, i) ?: return null // exclusive
            if (isWanted && found == null) found = json.copyOfRange(valueStart, valueEnd)
            i = skipWhitespace(json, valueEnd)
            if (i >= json.size) return null
            when (json[i]) {
                ','.code.toByte() -> i = skipWhitespace(json, i + 1)
                '}'.code.toByte() -> {
                    // Only whitespace may follow the top-level object.
                    return if (skipWhitespace(json, i + 1) == json.size) found else null
                }
                else -> return null
            }
        }
    }

    private fun skipBom(b: ByteArray): Int =
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) 3 else 0

    private fun skipWhitespace(b: ByteArray, from: Int): Int {
        var i = from
        while (i < b.size) {
            when (b[i].toInt()) {
                ' '.code, '\t'.code, '\n'.code, '\r'.code -> i++
                else -> return i
            }
        }
        return i
    }

    /** Given b[start] == '"', the index of the closing quote. */
    private fun stringEnd(b: ByteArray, start: Int): Int? {
        var i = start + 1
        while (i < b.size) {
            when (b[i]) {
                '\\'.code.toByte() -> i += 2
                '"'.code.toByte() -> return i
                else -> i++
            }
        }
        return null
    }

    /** The exclusive end of the value starting at [start]. */
    private fun valueEnd(b: ByteArray, start: Int): Int? {
        if (start >= b.size) return null
        return when (b[start]) {
            '"'.code.toByte() -> stringEnd(b, start)?.plus(1)
            '{'.code.toByte(), '['.code.toByte() -> containerEnd(b, start)
            else -> {
                // number, true, false, null: up to the next delimiter
                var i = start
                while (i < b.size) {
                    when (b[i].toInt()) {
                        ','.code, '}'.code, ']'.code, ' '.code, '\t'.code, '\n'.code, '\r'.code -> break
                        else -> i++
                    }
                }
                if (i == start) null else i
            }
        }
    }

    private fun containerEnd(b: ByteArray, start: Int): Int? {
        var depth = 0
        var i = start
        while (i < b.size) {
            when (b[i]) {
                '"'.code.toByte() -> {
                    i = stringEnd(b, i) ?: return null
                }
                '{'.code.toByte(), '['.code.toByte() -> depth++
                '}'.code.toByte(), ']'.code.toByte() -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return null
    }
}
