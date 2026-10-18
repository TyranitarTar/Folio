package app.folio.reader

import android.content.Context
import org.json.JSONObject
import java.net.URI

internal data class VoiceSample(val url: String, val bytes: Long, val sha256: String)
internal data class PackVoice(val id: String, val label: String, val language: String, val sample: VoiceSample?)
internal data class VoicePack(val id: String, val backend: String, val label: String, val version: String,
    val languages: List<String>, val url: String, val bytes: Long, val sha256: String, val expandedBytes: Long,
    val files: List<SpeechModelFile>, val required: Set<String>, val voices: List<PackVoice>, val license: String)

/** Identities are pinned by the signed APK; a downloaded pack cannot authorize itself. */
internal object VoicePackCatalog {
    fun load(context: Context): List<VoicePack> = parse(context.assets.open("voice-packs.json").bufferedReader().use { it.readText() })
    fun parse(raw: String): List<VoicePack> {
        require(raw.toByteArray().size <= 1_000_000)
        BoundedJson.check(raw, 16, 20000)
        val j=JSONObject(raw);require(j.getInt("schema")==1)
        val packs=j.getJSONArray("packs");require(packs.length() in 1..32)
        return (0 until packs.length()).map { n ->
            val p=packs.getJSONObject(n)
            val id=p.getString("id").also { require(it.matches(Regex("[a-z0-9-]{1,48}"))) }
            val version=p.getString("version").also { require(it.matches(Regex("[a-zA-Z0-9._-]{1,160}"))) }
            val backend=p.getString("backend").also { require(it in setOf("kokoro","supertonic","pico","plapre3")) }
            require(p.getString("runtime")==when(backend){"plapre3"->"plapre3-fp32-onnx-v1";"pico"->"pico-onnx-v1";else->"sherpa-onnx-1.13.8"})
            val fileLimit=if(backend=="plapre3")2L*1024*1024*1024 else 512L*1024*1024
            val expandedLimit=if(backend=="plapre3")3L*1024*1024*1024 else 768L*1024*1024
            val archiveLimit=if(backend=="plapre3")2L*1024*1024*1024-1 else 512L*1024*1024
            val array=p.getJSONArray("files");require(array.length() in 1..5000)
            val required=mutableSetOf<String>()
            val files=(0 until array.length()).map { i -> val f=array.getJSONObject(i)
                val name=f.getString("name").also(::safeName)
                if(f.optBoolean("required",true))required.add(name)
                SpeechModelFile(name,f.getLong("bytes").also { require(it in 0..fileLimit) },hash(f.getString("sha256")))
            }
            require(files.map { it.name }.distinct().size==files.size && required.isNotEmpty())
            val expanded=p.getLong("expandedBytes");require(expanded==files.sumOf { it.bytes } && expanded in 1..expandedLimit)
            val langs=p.getJSONArray("languages");val languages=(0 until langs.length()).map { langs.getString(it).also { l -> require(l in setOf("en","da")) } }.distinct()
            require(languages.isNotEmpty())
            val v=p.getJSONArray("voices");require(v.length() in 1..100)
            val voices=(0 until v.length()).map { i -> val voice=v.getJSONObject(i); val sample=voice.optJSONObject("sample")
                PackVoice(voice.getString("id").also { require(it.matches(Regex("[0-9]{1,3}"))) },voice.getString("label").take(100),
                    voice.getString("language").also { require(it in languages) },sample?.let { VoiceSample(url(it.getString("url")),
                        it.getLong("bytes").also { size -> require(size in 1..2_000_000) },hash(it.getString("sha256"))) })
            }
            require(voices.map { it.id to it.language }.distinct().size==voices.size)
            VoicePack(id,backend,p.getString("label").take(100),version,languages,url(p.getString("url")),
                p.getLong("bytes").also { require(it in 1..archiveLimit) },hash(p.getString("sha256")),expanded,files,required,voices,p.getString("license").take(2000))
        }.also { require(it.map { p -> p.id }.distinct().size==it.size) }
    }
    fun safeName(name: String) { require(name.isNotBlank() && name.length<=256 && '\\' !in name && !name.startsWith('/') && ':' !in name && name.split('/').none { it in setOf(".","..","") }) { "Unsafe voice pack path." } }
    private fun hash(raw: String)=raw.also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    fun url(raw: String): String {
        val uri=URI(raw)
        require(uri.scheme=="https" && uri.host=="github.com" && uri.userInfo==null && uri.port==-1 && uri.rawQuery==null && uri.fragment==null &&
            uri.path.startsWith("/TyranitarTar/Folio/releases/download/") && uri.path.split('/').none { it in setOf(".","..") }) { "Unsupported voice-pack address." }
        return uri.toASCIIString()
    }
}
