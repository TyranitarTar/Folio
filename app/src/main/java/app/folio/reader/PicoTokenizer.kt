package app.folio.reader

import org.json.JSONObject
import java.text.Normalizer

/** Exact NFC + GPT-2 byte-level BPE for the pinned Pico tokenizer, without a network tokenizer. */
internal class PicoTokenizer(raw: String) {
    private val data = JSONObject(raw)
    private val model = data.getJSONObject("model")
    private val vocabulary = model.getJSONObject("vocab")
    private val ranks = buildMap<String, Int> {
        val merges = model.getJSONArray("merges")
        for (i in 0 until merges.length()) {
            val pair = merges.get(i)
            val key = if (pair is org.json.JSONArray) pair.getString(0) + "\u0000" + pair.getString(1)
                else (pair as String).replaceFirst(" ", "\u0000")
            put(key, i)
        }
    }
    private val special = buildMap<String, Int> {
        val tokens = data.getJSONArray("added_tokens")
        for (i in 0 until tokens.length()) tokens.getJSONObject(i).let { put(it.getString("content"), it.getInt("id")) }
    }
    val eos = special.getValue("<eos>")
    val audioStart = special.getValue("<audio_0>")
    val audioEnd = special["</audio>"]
    private val byteCharacters = Array(256) { "" }.also { table ->
        val visible = ((33..126).toList() + (161..172).toList() + (174..255).toList()).toSet()
        var extra = 256
        for (value in 0..255) table[value] = (if (value in visible) value else extra++).toChar().toString()
    }
    init {
        require(model.getString("type") == "BPE" && data.getJSONObject("normalizer").getString("type") == "NFC")
        require(data.getJSONObject("pre_tokenizer").getString("type") == "ByteLevel")
        require(!data.getJSONObject("pre_tokenizer").getBoolean("add_prefix_space"))
        require(eos == 0 && audioStart == 8002 && special.getValue("<audio_12799>") == 20801)
        require(audioEnd==null || audioEnd==21223)
    }
    fun encode(text: String): IntArray {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val result = ArrayList<Int>()
        for (part in pattern.findAll(normalized)) {
            // This tokenizer has no unknown token or byte fallback. Match its
            // reference behavior for bytes absent from the training alphabet.
            val symbols = part.value.toByteArray(Charsets.UTF_8).map { byteCharacters[it.toInt() and 255] }
                .filter { vocabulary.has(it) }.toMutableList()
            while (symbols.size > 1) {
                var best = Int.MAX_VALUE; var at = -1
                for (i in 0 until symbols.lastIndex) {
                    val rank = ranks[symbols[i] + "\u0000" + symbols[i + 1]] ?: continue
                    if (rank < best) { best = rank; at = i }
                }
                if (at < 0) break
                symbols[at] += symbols[at + 1]; symbols.removeAt(at + 1)
            }
            symbols.forEach { result.add(vocabulary.getInt(it)) }
        }
        return result.toIntArray()
    }
    fun prompt(text: String): IntArray = intArrayOf(special.getValue("<text>")) + encode(text) + special.getValue("<audio>")
    companion object {
        private const val white = "\\p{Z}\\t\\n\\u000B\\u000C\\r\\u0085"
        private val pattern = Regex("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^${white}\\p{L}\\p{N}]+|[${white}]+(?![^${white}])|[${white}]+")
    }
}
