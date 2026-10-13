package app.folio.reader

import org.json.JSONObject

/** Check parsing work before Android's recursive JSON parser allocates the object tree. */
internal object BoundedJson {
    fun check(text: String, maxDepth: Int = 64, maxTokens: Int = 300000) {
        var depth = 0; var tokens = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                continue
            }
            if (c == '"') { quoted = true; continue }
            when (c) {
                '{', '[' -> { depth++; tokens++; require(depth <= maxDepth) { "The JSON data is too deeply nested." } }
                '}', ']' -> depth--
                ',', ':' -> tokens++
            }
            require(tokens <= maxTokens) { "The JSON data contains too many values." }
            require(depth >= 0) { "The JSON data is malformed." }
        }
        // The parser validates syntax, matching bracket types and quoted values.
    }
    fun objectFrom(bytes: ByteArray, maxBytes: Int, maxDepth: Int = 64, maxTokens: Int = 300000): JSONObject {
        require(bytes.size <= maxBytes) { "The JSON data exceeds its size limit." }
        val text = bytes.toString(Charsets.UTF_8)
        check(text, maxDepth, maxTokens)
        return JSONObject(text)
    }
}
